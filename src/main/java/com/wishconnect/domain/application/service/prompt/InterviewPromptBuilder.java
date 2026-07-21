package com.wishconnect.domain.application.service.prompt;

import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.application.entity.AiInterview;
import com.wishconnect.domain.application.entity.EssayQuestion;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * STEP1 사전 인터뷰용 LLM 프롬프트 조립기.
 *
 * <p>장학금·문항 컨텍스트로 시스템 프롬프트를 구성하고, 지금까지의 인터뷰 이력을
 * user/assistant 교대 메시지로 변환한다. LLM 응답이 인터뷰 완료 마커를 포함하는지도 판정한다.
 */
@Component
public class InterviewPromptBuilder {

	/** 문항당 최대 인터뷰 턴 수 (Notion 스펙 검토사항: 4~5턴 권장). */
	public static final int MAX_TURNS = 5;

	/** LLM 이 재료가 충분하다고 판단했을 때 출력하도록 지시하는 완료 마커. */
	public static final String COMPLETE_MARKER = "[INTERVIEW_COMPLETE]";

	/** 인터뷰 부트스트랩 시 첫 user 메시지 (모델에 첫 질문을 요청). */
	private static final String KICKOFF_USER_MESSAGE = "인터뷰를 시작해주세요.";

	/**
	 * @param scholarship 대상 장학금
	 * @param question    현재 인터뷰 중인 문항
	 * @param history     stepOrder 오름차순 인터뷰 이력. 이력의 마지막 항목까지 answerText 가
	 *                    채워진 상태여야 한다 (LLM 이 다음 질문을 생성하도록).
	 */
	public LlmChatRequest build(Scholarship scholarship,
			EssayQuestion question,
			List<AiInterview> history) {
		String systemPrompt = buildSystemPrompt(scholarship, question);
		List<LlmMessage> messages = buildMessages(history);
		return LlmChatRequest.of(LlmModel.INTERVIEW, systemPrompt, messages);
	}

	/**
	 * LLM 응답이 인터뷰 완료 마커를 포함하는지 판정.
	 */
	public boolean isComplete(String response) {
		return response != null && response.contains(COMPLETE_MARKER);
	}

	private String buildSystemPrompt(Scholarship scholarship, EssayQuestion question) {
		return """
				당신은 대학생의 장학금 지원 자기소개서 작성을 돕는 인터뷰어입니다.

				<context>
				장학금: %s (%s)
				문항: %s
				문항 설명: %s
				</context>

				역할과 규칙:
				- 학생의 경험과 생각을 구체적으로 끌어내는 한 번에 하나의 열린 질문만 던집니다.
				- 이전 답변을 기반으로 자연스럽게 파고들거나 확장합니다.
				- 답변에 대한 평가·조언·요약을 하지 마세요. 오직 질문만 하세요.
				- 짧고 명확한 한국어로 작성하세요.

				인터뷰 완료 판정:
				- %d턴을 넘기지 마세요.
				- 자기소개서 초안을 쓸 수 있을 만큼 구체적 경험·생각을 확보했다고 판단되면,
				  다음 질문 대신 정확히 다음 문자열만 출력하세요: %s
				""".formatted(
						nullSafe(scholarship.getTitle()),
						nullSafe(scholarship.getProvider()),
						nullSafe(question.getQuestionTitle()),
						nullSafe(question.getQuestionDescription()),
						MAX_TURNS,
						COMPLETE_MARKER);
	}

	private List<LlmMessage> buildMessages(List<AiInterview> history) {
		List<LlmMessage> messages = new ArrayList<>();
		messages.add(LlmMessage.user(KICKOFF_USER_MESSAGE));

		for (AiInterview turn : history) {
			messages.add(LlmMessage.assistant(turn.getQuestionText()));
			if (turn.getAnswerText() != null && !turn.getAnswerText().isBlank()) {
				messages.add(LlmMessage.user(turn.getAnswerText()));
			}
		}
		return messages;
	}

	private String nullSafe(String value) {
		return value == null ? "" : value;
	}
}
