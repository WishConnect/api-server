package com.wishconnect.domain.application.service.prompt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * 면접 예상 질문 생성 프롬프트 조립기.
 *
 * <p>{@link InterviewPromptBuilder}(사전 인터뷰)와 목적이 다르다. 저쪽은 자기소개서를 쓸 재료를
 * 모으려고 <b>AI 가 사용자에게</b> 묻는 질문을 만들고, 이쪽은 <b>면접관이 지원자에게</b> 물어볼
 * 법한 질문을 예측한다. 사용자 답변을 받지 않는다.
 *
 * <p>질문 하나마다 화면에 네 가지가 함께 나간다.
 * <ul>
 *   <li><b>질문의도</b> — 면접관이 이 질문으로 무엇을 보려는지</li>
 *   <li><b>답변 Tip</b> — 답할 때 유의할 점</li>
 *   <li><b>구성 가이드</b> — STEP1 → STEP2 → STEP3 답변 뼈대</li>
 *   <li>예시답변 — 여기서 만들지 않는다. 사용자가 쓴 자소서를 재료로
 *       {@link InterviewSampleAnswerPromptBuilder} 가 따로 만든다</li>
 * </ul>
 *
 * <p>예시답변만 떼어낸 이유는 <b>캐시 단위가 다르기 때문</b>이다. 질문·의도·Tip·가이드는 누가
 * 보든 같은 내용이라 장학금 단위로 한 번 만들어 공유하지만, 예시답변은 사람마다 달라야 한다.
 *
 * <p>모델은 {@link LlmModel#INTERVIEW}(기본 Haiku)를 쓴다. 장학금당 한 번만 만들어 캐시하므로
 * 호출량이 많지 않고, 초안 생성만큼 문체 품질이 중요하지도 않다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewPrepPromptBuilder {

	/** 장학금당 생성할 면접 예상 질문 개수. */
	public static final int QUESTION_COUNT = 6;

	/** 답변 구성 가이드 단계 수. 화면이 STEP1~3 흐름으로 그린다. */
	public static final int GUIDE_STEP_COUNT = 3;

	private static final int MIN_QUESTION_LENGTH = 8;
	private static final int MAX_QUESTION_LENGTH = 70;
	private static final int MAX_INTENT_LENGTH = 120;
	private static final int MAX_TIP_LENGTH = 120;

	/** 일반 예시답변 길이. 면접에서 1분 안에 말할 분량을 넘기면 준비 자료로 쓸모가 떨어진다. */
	private static final int MAX_SAMPLE_LENGTH = 600;
	private static final int MIN_SAMPLE_LENGTH = 40;

	/** 단계 이름은 화면 배지에 들어가야 해서 짧아야 한다. */
	private static final int MAX_STEP_TITLE_LENGTH = 20;
	private static final int MAX_STEP_DESCRIPTION_LENGTH = 150;

	/** 프롬프트에 넣을 자격조건 개수·길이 상한. 전부 넣으면 토큰만 늘고 질문이 산만해진다. */
	private static final int MAX_CONDITIONS = 8;
	private static final int MAX_CONDITION_LENGTH = 120;

	private final ObjectMapper objectMapper;

	/**
	 * 장학금 하나에 대한 면접 예상 질문 생성 요청을 조립한다.
	 *
	 * @param scholarship 대상 장학금
	 * @param conditions  이 장학금의 자격조건. 없으면 빈 리스트를 넘긴다
	 */
	public LlmChatRequest build(Scholarship scholarship, List<ScholarshipCondition> conditions) {
		return LlmChatRequest.of(LlmModel.INTERVIEW,
				buildSystemPrompt(scholarship, conditions),
				List.of(LlmMessage.user("면접 예상 질문을 만들어주세요.")));
	}

	/**
	 * 응답을 읽고 <b>쓸 수 있는 질문만</b> 남긴다.
	 *
	 * <p>모델이 형식을 어기면 머리말·맺음말이 섞여 들어오고, 값이 비거나 지나치게 길 수 있다.
	 * 저장 전에 걸러내지 않으면 화면에 질문이 아닌 문장이 뜬다.
	 *
	 * @return 검증을 통과한 질문. 하나도 없으면 빈 리스트
	 */
	public List<GeneratedQuestion> parse(String response) {
		List<Raw> raws = readJson(response);
		if (raws.isEmpty()) {
			return List.of();
		}

		List<GeneratedQuestion> accepted = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Raw raw : raws) {
			GeneratedQuestion question = validate(raw, seen);
			if (question != null) {
				accepted.add(question);
			}
			if (accepted.size() >= QUESTION_COUNT) {
				break;
			}
		}
		return List.copyOf(accepted);
	}

	/** 질문 하나를 검증한다. 질문 자체가 성립하지 않으면 버리고, 부가 정보는 없으면 비운다. */
	private GeneratedQuestion validate(Raw raw, Set<String> seen) {
		String question = clean(raw.question());
		if (question == null
				|| question.length() < MIN_QUESTION_LENGTH
				|| question.length() > MAX_QUESTION_LENGTH
				|| !seen.add(question)) {
			return null;
		}
		return new GeneratedQuestion(
				question,
				truncate(clean(raw.intent()), MAX_INTENT_LENGTH),
				truncate(clean(raw.answerTip()), MAX_TIP_LENGTH),
				sampleAnswerOf(raw),
				guideStepsOf(raw));
	}

	/** 너무 짧은 예시답변은 답변 구실을 못 하므로 버린다. 없다고 질문까지 버리지는 않는다. */
	private String sampleAnswerOf(Raw raw) {
		String answer = clean(raw.sampleAnswer());
		if (answer == null || answer.length() < MIN_SAMPLE_LENGTH) {
			return null;
		}
		return truncate(answer, MAX_SAMPLE_LENGTH);
	}

	/**
	 * 구성 가이드를 정리한다.
	 *
	 * <p>단계가 하나라도 어긋나면 <b>가이드 전체를 비운다.</b> 반쪽짜리 흐름(STEP1 만 있고 2·3 이
	 * 없는 상태)은 화면에서 더 혼란스럽다. 질문·의도·Tip 은 그대로 살린다.
	 */
	private List<GuideStep> guideStepsOf(Raw raw) {
		List<Raw.Step> steps = raw.safeGuide();
		if (steps.size() < GUIDE_STEP_COUNT) {
			return List.of();
		}
		List<GuideStep> result = new ArrayList<>();
		for (Raw.Step step : steps.subList(0, GUIDE_STEP_COUNT)) {
			String title = clean(step.title());
			String description = clean(step.description());
			if (title == null || title.isEmpty() || title.length() > MAX_STEP_TITLE_LENGTH
					|| description == null || description.isEmpty()) {
				return List.of();
			}
			result.add(new GuideStep(title, truncate(description, MAX_STEP_DESCRIPTION_LENGTH)));
		}
		return List.copyOf(result);
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
			log.warn("[InterviewPrep] 응답을 읽지 못했습니다: {}", e.getMessage());
			return List.of();
		}
	}

	private String buildSystemPrompt(Scholarship scholarship, List<ScholarshipCondition> conditions) {
		return """
				당신은 한국 대학 장학금 면접을 준비하는 학생을 돕는 코치입니다.
				반드시 JSON 배열만 출력한다(설명·코드펜스 금지).

				<장학금>
				이름: %s
				주관: %s
				안내: %s
				선발 관련 공고 문장: %s
				지원 자격: %s
				</장학금>

				과제:
				이 장학금 면접에서 면접관이 물어볼 법한 질문 %d개를 만들고,
				각 질문마다 답변 준비 자료를 함께 작성하세요.

				출력 형식:
				[{"question":"질문 한 문장",
				  "intent":"면접관이 이 질문으로 무엇을 보려는지 한 문장",
				  "answerTip":"답변할 때 유의할 점 한 문장",
				  "sampleAnswer":"이 질문에 답하는 예시답변",
				  "guide":[{"title":"단계 이름","description":"이 단계에서 무엇을 말할지"},
				           {"title":"단계 이름","description":"..."},
				           {"title":"단계 이름","description":"..."}]}]

				규칙:
				- 이 장학금의 성격과 지원 자격에 맞는 질문을 쓰세요. 어떤 장학금에나 통하는
				  일반적인 질문("자기소개 해주세요")은 최대 1개까지만 허용합니다.
				- %d개 질문은 서로 다른 각도를 다루세요. 예: 지원 동기와 이 장학금을 고른 이유,
				  학업·활동에서의 구체적 경험, 어려움을 겪고 대응한 사례, 자격 요건과 본인 상황의 연결,
				  수혜 후 계획과 기여.
				- 공고에 없는 사실을 지어내지 마세요. 자격 요건이 주어지지 않았다면 그것을 전제한
				  질문을 만들지 마세요.
				- question 은 한 문장, %d자 이내의 한국어로 쓰세요.
				- guide 는 정확히 %d단계로, 답변의 흐름이 되게 쓰세요.
				  예: "강점제시"(핵심을 먼저 밝힌다) → "경험 설명"(상황·행동·결과 순서로 뒷받침한다)
				      → "성장 및 활용"(무엇을 배웠고 앞으로 어떻게 쓸지 마무리한다).
				  title 은 %d자 이내의 짧은 이름으로 쓰세요.
				- sampleAnswer 는 학생이 참고할 예시답변입니다. %d자 이내로, 학생 본인이 말하는
				  1인칭으로 쓰세요. **특정 수상 실적·회사명·수치처럼 확인할 수 없는 사실은 넣지 마세요.**
				  누가 읽어도 자기 경험으로 바꿔 넣을 수 있게, 흐름과 표현을 보여주는 데 집중하세요.
				- 평가·조언·머리말·맺음말을 쓰지 마세요.
				""".formatted(
						nullSafe(scholarship.getTitle()),
						nullSafe(scholarship.getProvider()),
						summaryOf(scholarship),
						nullSafe(scholarship.getInterviewEvidence()),
						conditionsOf(conditions),
						QUESTION_COUNT,
						QUESTION_COUNT,
						MAX_QUESTION_LENGTH,
						GUIDE_STEP_COUNT,
						MAX_STEP_TITLE_LENGTH,
						MAX_SAMPLE_LENGTH);
	}

	/** 요약이 없으면 설명 앞부분으로 대신한다. 둘 다 없으면 빈 문자열이다. */
	private String summaryOf(Scholarship scholarship) {
		if (scholarship.getSummary() != null && !scholarship.getSummary().isBlank()) {
			return scholarship.getSummary();
		}
		String description = scholarship.getDescription();
		if (description == null || description.isBlank()) {
			return "";
		}
		return truncate(description, 500);
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

	private static String clean(String value) {
		return value == null ? null : value.replaceAll("\\s+", " ").trim();
	}

	private static String truncate(String value, int max) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		return value.length() > max ? value.substring(0, max) : value;
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}

	/** LLM 원본 응답 한 건. 검증 전이라 값이 어긋나 있을 수 있다. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Raw(String question, String intent, String answerTip, String sampleAnswer,
			List<Step> guide) {

		List<Step> safeGuide() {
			return guide == null ? List.of() : guide;
		}

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Step(String title, String description) {
		}
	}

	/** 검증을 통과한 질문. 가이드가 온전하지 않으면 {@code guideSteps} 는 빈 리스트다. */
	public record GeneratedQuestion(String questionText, String intent, String answerTip,
			String sampleAnswer, List<GuideStep> guideSteps) {
	}

	/** 답변 구성 가이드 한 단계. */
	public record GuideStep(String title, String description) {
	}
}
