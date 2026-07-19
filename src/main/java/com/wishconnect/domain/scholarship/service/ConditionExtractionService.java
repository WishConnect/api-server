package com.wishconnect.domain.scholarship.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.scholarship.dto.ConditionExtractionResponse;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.util.ConditionRuleParser;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
LLM(Phase 0)으로 조건 원문(valueString)에서 구조화 수치를 추출합니다.
예: "소득 8분위 이하" -> LTE 8, "평점 2.75 이상" -> GTE 275, "대학2학기~8학기" -> BETWEEN 2~8.
추출 성공 시 autoExtracted=true 로 마킹하고 원문은 검증용으로 보존합니다.
확신 없는 값은 null 로 두게 하여(스킵) 잘못된 배제를 막는 것을 우선합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConditionExtractionService {

	private static final List<ConditionType> EXTRACTABLE_TYPES = List.of(
			ConditionType.INCOME_CRITERIA, ConditionType.ACADEMIC_CRITERIA, ConditionType.GRADE_LEVEL);

	private static final String SYSTEM_PROMPT = """
			너는 한국 장학금 자격조건 텍스트에서 수치 기준을 추출하는 도구다.
			입력의 각 줄은 "id|유형|원문" 형식이다. 반드시 JSON 배열만 출력한다(설명·코드펜스 금지).
			각 원소: {"id":숫자,"operator":"GTE|LTE|BETWEEN|EQ","valueInt":숫자|null,"valueIntMax":숫자|null}
			규약:
			- INCOME_CRITERIA(소득): 분위 숫자(1~10). "N분위 이하/이내" -> operator LTE, valueInt N.
			- ACADEMIC_CRITERIA(성적): 평점은 100배 정수(2.75 -> 275, 만점 4.5 기준). "이상" -> GTE.
			- GRADE_LEVEL(학년/학기): 학기 단위 숫자. "대학N학기~M학기" -> BETWEEN N~M. "N학년"은 학기로 2N-1~2N.
			- 수치 기준이 없거나 확신이 없으면 valueInt를 null 로 둔다(추측 금지).
			""";

	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final LlmClient llmClient;
	private final ObjectMapper objectMapper;

	@Transactional
	public ConditionExtractionResponse extract() {
		var targets = scholarshipConditionRepository
				.findTop50ByAutoExtractedFalseAndValueIntIsNullAndConditionTypeIn(EXTRACTABLE_TYPES);
		if (targets.isEmpty()) {
			return new ConditionExtractionResponse(0, 0, 0);
		}

		// 1차: 룰 기반(정규식) 추출 — LLM 없이도 정형 패턴은 확정 처리한다.
		int ruleExtracted = 0;
		var unresolved = new java.util.ArrayList<com.wishconnect.domain.scholarship.entity.ScholarshipCondition>();
		for (var condition : targets) {
			var parsed = ConditionRuleParser.parse(condition.getConditionType(), condition.getValueString());
			if (parsed.isPresent()) {
				var extractedValue = parsed.get();
				condition.applyExtracted(extractedValue.operator(), extractedValue.valueInt(),
						extractedValue.valueIntMax());
				ruleExtracted++;
			} else {
				unresolved.add(condition);
			}
		}

		// 2차: 룰로 못 푼 비정형 조건만 LLM에 위임. 실패해도 룰 결과는 유지한다.
		int llmExtracted = 0;
		if (!unresolved.isEmpty()) {
			llmExtracted = tryLlmExtract(unresolved);
		}

		int extracted = ruleExtracted + llmExtracted;
		log.info("[ConditionExtraction] 대상={} 룰추출={} LLM추출={} 스킵={}",
				targets.size(), ruleExtracted, llmExtracted, targets.size() - extracted);
		return new ConditionExtractionResponse(targets.size(), extracted, targets.size() - extracted);
	}

	/** LLM 추출 시도. 크레딧 부족·키 미설정 등 어떤 실패도 전체 추출을 깨뜨리지 않는다. */
	private int tryLlmExtract(List<com.wishconnect.domain.scholarship.entity.ScholarshipCondition> targets) {
		try {
			var targetById = targets.stream()
					.collect(Collectors.toMap(condition -> condition.getId(), Function.identity()));
			String input = targets.stream()
					.map(condition -> condition.getId() + "|" + condition.getConditionType() + "|"
							+ condition.getValueString().replaceAll("\\s+", " ").trim())
					.collect(Collectors.joining("\n"));
			String rawResponse = llmClient.chat(LlmChatRequest.of(
					LlmModel.INTERVIEW, SYSTEM_PROMPT, List.of(LlmMessage.user(input))));
			int extracted = 0;
			for (ExtractedCondition item : parse(rawResponse)) {
				var condition = targetById.get(item.id());
				if (condition == null || !isValid(condition.getConditionType(), item)) {
					continue;
				}
				condition.applyExtracted(toOperator(item.operator()), item.valueInt(), item.valueIntMax());
				extracted++;
			}
			return extracted;
		} catch (Exception e) {
			log.warn("[ConditionExtraction] LLM 추출 생략(룰 결과는 유지): {}", e.getMessage());
			return 0;
		}
	}

	private List<ExtractedCondition> parse(String rawResponse) {
		try {
			String json = rawResponse.strip();
			if (json.startsWith("```")) {
				json = json.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").strip();
			}
			return objectMapper.readValue(json, new TypeReference<>() {
			});
		} catch (Exception e) {
			log.warn("[ConditionExtraction] LLM 응답 파싱 실패: {}", e.getMessage());
			return List.of();
		}
	}

	/** 유형별 값 범위를 검증해 LLM 오추출로 인한 잘못된 배제를 막는다. */
	private boolean isValid(ConditionType type, ExtractedCondition item) {
		Integer value = item.valueInt();
		if (value == null) {
			return false;
		}
		return switch (type) {
			case INCOME_CRITERIA -> value >= 1 && value <= 10;
			case ACADEMIC_CRITERIA -> value >= 100 && value <= 450;
			case GRADE_LEVEL -> value >= 1 && value <= 12
					&& (item.valueIntMax() == null || (item.valueIntMax() >= value && item.valueIntMax() <= 12));
			default -> false;
		};
	}

	private ConditionOperator toOperator(String operator) {
		try {
			return operator == null ? null : ConditionOperator.valueOf(operator);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private record ExtractedCondition(Long id, String operator, Integer valueInt, Integer valueIntMax) {
	}
}
