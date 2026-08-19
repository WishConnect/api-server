package com.wishconnect.domain.application.service.prompt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.application.entity.InterviewPrepQuestion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 면접 예시답변 생성 프롬프트 조립기.
 *
 * <p>질문은 장학금 단위로 공유하지만 예시답변은 <b>학생이 쓴 자기소개서를 재료로</b> 만든다.
 * 기획이 "작성한 자기소개서를 바탕으로" 만들도록 정했고, 남의 경험이 적힌 모범답안을 주면
 * 그대로 베끼게 되어 면접에서 오히려 불리하다.
 *
 * <p><b>자소서에 없는 경험을 지어내지 않게 하는 것이 핵심이다.</b> 프롬프트에서 자소서에 적힌
 * 사실만 쓰도록 못박고, 재료가 부족하면 그 질문은 건너뛰게 한다. 빈 답을 주는 편이
 * 없는 수상 경력·인턴 경험이 적힌 답을 주는 것보다 낫다.
 *
 * <p>모델은 {@link LlmModel#DRAFT}(기본 Sonnet)를 쓴다. 예시답변은 사용자가 그대로 읽고 말로
 * 옮기는 글이라 문체 품질이 결과물 자체다. 자소서 초안과 같은 이유로 같은 모델을 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewSampleAnswerPromptBuilder {

	/** 예시답변 길이 상한. 면접에서 1분 안에 말할 분량을 넘기면 준비 자료로 쓸모가 떨어진다. */
	private static final int MAX_ANSWER_LENGTH = 600;

	/** 이보다 짧으면 답변 구실을 못 한다. */
	private static final int MIN_ANSWER_LENGTH = 40;

	/** 프롬프트에 넣을 자소서 분량. 전문을 넣으면 토큰이 급증한다. */
	private static final int MAX_ESSAY_CHARS = 4_000;

	/** 자소서가 이보다 짧으면 재료가 없다고 보고 호출 자체를 하지 않는다. */
	public static final int MIN_ESSAY_CHARS = 100;

	private final ObjectMapper objectMapper;

	/**
	 * 예시답변 생성 요청을 조립한다.
	 *
	 * @param questions   답변을 만들 질문 목록
	 * @param essayText   학생이 쓴 자기소개서 (문항별 답변을 이어 붙인 것)
	 * @param scholarship 장학금 이름. 답변 톤을 맞추는 데만 쓴다
	 */
	public LlmChatRequest build(List<InterviewPrepQuestion> questions, String essayText,
			String scholarship) {
		StringBuilder questionList = new StringBuilder();
		for (InterviewPrepQuestion question : questions) {
			questionList.append(question.getId()).append(". ")
					.append(question.getQuestionText()).append('\n');
		}
		return LlmChatRequest.of(LlmModel.DRAFT,
				buildSystemPrompt(scholarship, essayText, questionList.toString()),
				List.of(LlmMessage.user("예시답변을 만들어주세요.")));
	}

	/**
	 * 응답을 읽어 질문 ID 별 답변으로 정리한다.
	 *
	 * <p>목록에 없는 ID 를 만들어냈거나 길이가 어긋난 답변은 버린다. 일부만 살아남아도
	 * 그대로 쓴다 — 답변이 있는 질문만 화면에 예시가 붙고 나머지는 질문·Tip·가이드만 보인다.
	 *
	 * @return 질문 ID → 예시답변
	 */
	public Map<Long, String> parse(String response, List<InterviewPrepQuestion> questions) {
		List<Raw> raws = readJson(response);
		if (raws.isEmpty()) {
			return Map.of();
		}
		List<Long> allowed = new ArrayList<>();
		for (InterviewPrepQuestion question : questions) {
			allowed.add(question.getId());
		}

		Map<Long, String> result = new LinkedHashMap<>();
		for (Raw raw : raws) {
			if (raw.questionId() == null || !allowed.contains(raw.questionId())) {
				// 목록에 없는 ID = 모델이 만들어낸 것. 엉뚱한 질문에 답이 붙는다.
				continue;
			}
			String answer = raw.answer() == null ? "" : raw.answer().replaceAll("\\s+", " ").trim();
			if (answer.length() < MIN_ANSWER_LENGTH) {
				continue;
			}
			result.putIfAbsent(raw.questionId(),
					answer.length() > MAX_ANSWER_LENGTH ? answer.substring(0, MAX_ANSWER_LENGTH) : answer);
		}
		return Map.copyOf(result);
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
			log.warn("[InterviewPrep] 예시답변 응답을 읽지 못했습니다: {}", e.getMessage());
			return List.of();
		}
	}

	private String buildSystemPrompt(String scholarship, String essayText, String questionList) {
		return """
				당신은 한국 대학 장학금 면접을 준비하는 학생을 돕는 코치입니다.
				반드시 JSON 배열만 출력한다(설명·코드펜스 금지).

				<지원 장학금>
				%s
				</지원 장학금>

				<학생이 쓴 자기소개서>
				%s
				</학생이 쓴 자기소개서>

				<면접 예상 질문>
				%s
				</면접 예상 질문>

				과제:
				각 질문에 학생이 실제로 말할 수 있는 예시답변을 만드세요.

				출력 형식:
				[{"questionId":숫자,"answer":"예시답변"}]

				규칙:
				- **자기소개서에 적힌 사실만 쓰세요.** 자소서에 없는 수상 실적·인턴 경험·수치를
				  지어내면 안 됩니다. 면접에서 학생이 답하지 못하는 내용이 됩니다.
				- 어떤 질문에 쓸 재료가 자소서에 없으면 그 질문은 결과에서 빼세요. 억지로 채우지 마세요.
				- 학생 본인이 말하는 1인칭으로, 면접에서 1분 안에 말할 분량(%d자 이내)으로 쓰세요.
				- 핵심을 먼저 밝히고, 구체적인 경험으로 뒷받침하고, 배운 점으로 마무리하세요.
				- questionId 는 위 목록에 있는 숫자를 그대로 쓰세요. 새로 만들지 마세요.
				- 평가·조언·머리말·맺음말을 쓰지 마세요.
				""".formatted(
						nullSafe(scholarship),
						truncate(nullSafe(essayText), MAX_ESSAY_CHARS),
						questionList);
	}

	private static String truncate(String value, int max) {
		return value.length() > max ? value.substring(0, max) : value;
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Raw(Long questionId, String answer) {
	}
}
