package com.wishconnect.domain.application.service.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 장학금별 자기소개서 문항 생성 프롬프트 조립기.
 *
 * <p>지금까지 문항은 "지원 동기", "성장 배경 및 자기소개" 두 개로 고정이었다. 어떤 장학금이든
 * 같은 것을 물으니, 지역인재 장학금인지 이공계 연구 장학금인지가 글에 드러나지 않았다.
 * 이 빌더는 공고를 읽고 그 장학금에 맞는 <b>카테고리와 질문을 함께</b> 만든다.
 *
 * <h2>근거 없는 문항은 만들지 않는다</h2>
 * 공고에 없는 것을 물으면 학생이 없는 경험을 지어내게 된다("봉사 경험을 쓰세요" — 공고에 봉사
 * 얘기가 없는데). 그래서 문항마다 <b>공고에서 가져온 근거 문장</b>을 함께 내게 하고, 그 문장이
 * 실제 공고 텍스트에 있는지 대조한다. 파서가 기간·전형 판단에 쓰는 방식과 같다.
 *
 * <p>근거를 통과한 문항이 {@value #MIN_QUESTIONS} 개에 못 미치면 <b>기본 문항으로 되돌린다.</b>
 * 지어낸 맞춤 문항보다 무난한 고정 문항이 낫다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EssayQuestionPromptBuilder {

	/** 만들 문항 수 상한. 너무 많으면 학생이 지쳐 완주하지 못한다. */
	public static final int MAX_QUESTIONS = 4;

	/** 이보다 적게 살아남으면 맞춤 문항을 포기하고 기본 문항을 쓴다. */
	public static final int MIN_QUESTIONS = 2;

	/** 카테고리(제목) 길이. 화면 탭에 들어가야 해서 짧아야 한다. */
	private static final int MIN_TITLE_LENGTH = 2;
	private static final int MAX_TITLE_LENGTH = 20;

	/** 질문 본문 길이. */
	private static final int MIN_DESCRIPTION_LENGTH = 10;
	private static final int MAX_DESCRIPTION_LENGTH = 200;

	/** 글자수 제한 허용 범위. 벗어나면 모델이 임의로 지어낸 값이다. */
	private static final int MIN_CHAR_LIMIT = 300;
	private static final int MAX_CHAR_LIMIT = 2_000;
	private static final int DEFAULT_CHAR_LIMIT = 800;

	/** 근거로 인정할 최소 길이. 짧은 인용은 우연히 일치한다. */
	private static final int MIN_BASIS_LENGTH = 6;

	/** 프롬프트에 넣을 공고 본문 길이. 전문을 넣으면 토큰만 늘어난다. */
	private static final int MAX_BODY_CHARS = 3_000;
	private static final int MAX_CONDITIONS = 10;
	private static final int MAX_CONDITION_LENGTH = 120;

	private final ObjectMapper objectMapper;

	/**
	 * 문항 생성 요청을 조립한다.
	 *
	 * @param scholarship 대상 장학금
	 * @param conditions  이 장학금의 자격조건. 없으면 빈 리스트
	 */
	public LlmChatRequest build(Scholarship scholarship, List<ScholarshipCondition> conditions) {
		return LlmChatRequest.of(LlmModel.INTERVIEW,
				buildSystemPrompt(scholarship, conditions),
				List.of(LlmMessage.user("이 장학금에 맞는 자기소개서 문항을 만들어주세요.")));
	}

	/**
	 * 응답을 읽고 <b>공고에 근거가 있는 문항만</b> 남긴다.
	 *
	 * @param sourceText 근거를 대조할 공고 텍스트 (제목 + 요약 + 본문 + 자격조건)
	 * @return 살아남은 문항. {@value #MIN_QUESTIONS} 개 미만이면 빈 리스트 — 호출자가 기본 문항을 쓴다
	 */
	public List<GeneratedQuestion> parse(String response, String sourceText) {
		List<Raw> raws = readJson(response);
		if (raws.isEmpty()) {
			return List.of();
		}

		List<GeneratedQuestion> accepted = new ArrayList<>();
		Set<String> seenTitles = new LinkedHashSet<>();
		for (Raw raw : raws) {
			GeneratedQuestion question = validate(raw, sourceText, seenTitles);
			if (question != null) {
				accepted.add(question);
			}
			if (accepted.size() >= MAX_QUESTIONS) {
				break;
			}
		}

		if (accepted.size() < MIN_QUESTIONS) {
			log.info("근거를 통과한 문항이 {}개뿐이라 기본 문항을 씁니다. (요청 {}개)",
					accepted.size(), raws.size());
			return List.of();
		}
		return List.copyOf(accepted);
	}

	/** 문항 하나를 검증한다. 하나라도 어긋나면 버린다. */
	private GeneratedQuestion validate(Raw raw, String sourceText, Set<String> seenTitles) {
		String title = clean(raw.title());
		String description = clean(raw.description());
		if (!inRange(title, MIN_TITLE_LENGTH, MAX_TITLE_LENGTH)
				|| !inRange(description, MIN_DESCRIPTION_LENGTH, MAX_DESCRIPTION_LENGTH)) {
			return null;
		}
		if (!seenTitles.add(title)) {
			return null;
		}
		if (!isGrounded(raw.basis(), sourceText)) {
			log.debug("근거가 공고에 없어 버립니다. title={}, basis={}", title, raw.basis());
			return null;
		}
		return new GeneratedQuestion(title, description, resolveCharLimit(raw.charLimit()));
	}

	/**
	 * 근거 문장이 실제 공고에 있는지 대조한다.
	 *
	 * <p>공백·문장부호를 지우고 부분 문자열로 본다. 모델이 표기를 조금 바꿔 인용해도 통과시키되,
	 * 없는 내용을 지어낸 경우는 걸러내기 위한 절충이다.
	 */
	static boolean isGrounded(String basis, String sourceText) {
		if (basis == null || basis.isBlank() || sourceText == null) {
			return false;
		}
		String needle = normalize(basis);
		if (needle.length() < MIN_BASIS_LENGTH) {
			return false;
		}
		return normalize(sourceText).contains(needle);
	}

	private static String normalize(String value) {
		return value.replaceAll("[\\s\\p{Punct}·~∼〜]", "");
	}

	/** 모델이 범위를 벗어난 값을 주면 기본값으로 되돌린다. */
	private Integer resolveCharLimit(Integer value) {
		if (value == null || value < MIN_CHAR_LIMIT || value > MAX_CHAR_LIMIT) {
			return DEFAULT_CHAR_LIMIT;
		}
		return value;
	}

	private List<Raw> readJson(String response) {
		if (response == null || response.isBlank()) {
			return List.of();
		}
		try {
			String json = response.strip();
			if (json.startsWith("```")) {
				json = json.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").strip();
			}
			int start = json.indexOf('[');
			int end = json.lastIndexOf(']');
			if (start < 0 || end <= start) {
				return List.of();
			}
			Raw[] parsed = objectMapper.readValue(json.substring(start, end + 1), Raw[].class);
			return parsed == null ? List.of() : List.of(parsed);
		} catch (Exception e) {
			log.warn("문항 생성 응답을 읽지 못했습니다: {}", e.getMessage());
			return List.of();
		}
	}

	private static boolean inRange(String value, int min, int max) {
		return value != null && value.length() >= min && value.length() <= max;
	}

	private static String clean(String value) {
		return value == null ? null : value.replaceAll("\\s+", " ").trim();
	}

	/**
	 * 근거 대조에 쓸 공고 텍스트. 프롬프트에 넣는 것과 같은 재료여야 한다 —
	 * 모델에게 보여주지 않은 곳에서 인용했다고 통과시키면 검증이 무의미하다.
	 */
	public String sourceTextOf(Scholarship scholarship, List<ScholarshipCondition> conditions) {
		StringBuilder text = new StringBuilder();
		append(text, scholarship.getTitle());
		append(text, scholarship.getProvider());
		append(text, scholarship.getSummary());
		append(text, truncate(scholarship.getDescription(), MAX_BODY_CHARS));
		append(text, conditionsOf(conditions));
		return text.toString();
	}

	private void append(StringBuilder target, String value) {
		if (value != null && !value.isBlank()) {
			target.append(value).append('\n');
		}
	}

	private String buildSystemPrompt(Scholarship scholarship, List<ScholarshipCondition> conditions) {
		return """
				당신은 대학생의 장학금 지원 자기소개서를 설계하는 전문가입니다.
				반드시 JSON 배열만 출력한다(설명·코드펜스 금지).

				<장학금>
				이름: %s
				주관: %s
				요약: %s
				공고 본문: %s
				지원 자격: %s
				</장학금>

				과제:
				이 장학금에 맞는 자기소개서 문항을 2~%d개 만드세요.

				출력 형식:
				[{"title":"문항 이름","description":"학생에게 보여줄 질문","charLimit":800,
				  "basis":"이 문항이 필요하다고 본 근거를 공고에서 그대로 인용"}]

				규칙:
				- title 은 화면 탭에 들어가는 짧은 이름이다(2~20자). 예: "지원 동기", "연구 계획".
				- description 은 학생이 무엇을 써야 하는지 알려주는 한두 문장이다(10~200자).
				- charLimit 은 300~2000 사이의 정수로, 그 문항에 적당한 분량을 정한다.
				- **basis 는 공고에 실제로 있는 문장을 그대로 인용해야 한다.** 요약하거나 바꿔 쓰지 마라.
				  근거가 없는 문항은 만들지 마라 — 공고에 없는 것을 물으면 학생이 없는 경험을 지어낸다.
				- 이 장학금의 성격(선발 기준·지원 자격·지원 목적)이 드러나는 문항을 우선하라.
				- 서로 다른 것을 묻게 하라. 같은 내용을 이름만 바꿔 두 번 묻지 마라.
				- 근거가 될 만한 내용이 공고에 없으면 빈 배열 []을 출력하라. 지어내지 마라.
				""".formatted(
						nullSafe(scholarship.getTitle()),
						nullSafe(scholarship.getProvider()),
						nullSafe(scholarship.getSummary()),
						truncate(nullSafe(scholarship.getDescription()), MAX_BODY_CHARS),
						conditionsOf(conditions),
						MAX_QUESTIONS);
	}

	private String conditionsOf(List<ScholarshipCondition> conditions) {
		if (conditions == null || conditions.isEmpty()) {
			// 비워 두면 모델이 흔한 자격을 지어내기 쉬워, 없다는 사실을 명시한다.
			return "(공고에서 확인되지 않음)";
		}
		StringBuilder joined = new StringBuilder();
		int count = 0;
		for (ScholarshipCondition condition : conditions) {
			String value = clean(condition.getValueString());
			if (value == null || value.isBlank()) {
				continue;
			}
			joined.append(joined.isEmpty() ? "" : " / ")
					.append(truncate(value, MAX_CONDITION_LENGTH));
			if (++count >= MAX_CONDITIONS) {
				break;
			}
		}
		return joined.isEmpty() ? "(공고에서 확인되지 않음)" : joined.toString();
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return "";
		}
		return value.length() > max ? value.substring(0, max) : value;
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}

	/** LLM 원본 응답 한 건. 검증 전이라 값이 어긋나 있을 수 있다. */
	@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
	private record Raw(String title, String description, Integer charLimit, String basis) {
	}

	/** 검증을 통과한 문항. */
	public record GeneratedQuestion(String title, String description, Integer charLimit) {
	}
}
