package com.wishconnect.domain.application.service.prompt;

import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.application.entity.AiInterview;
import com.wishconnect.domain.application.entity.EssayQuestion;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * STEP2 자기소개서 초안 생성용 LLM 프롬프트 조립기.
 *
 * <p>사전 인터뷰 이력(질문·답변)을 컨텍스트로 넘겨 LLM(DRAFT 프로필=Sonnet)이 학생의 목소리로
 * 완결된 자기소개서 초안을 작성하도록 한다. 응답은 초안 본문만 포함해야 하며, 인터뷰에 없는
 * 사실을 만들어내지 않도록 규칙에 명시한다.
 */
@Component
public class DraftPromptBuilder {

	/** charLimit 이 지정되지 않은 문항의 기본 목표 글자수. */
	private static final int DEFAULT_CHAR_LIMIT = 500;

	public LlmChatRequest build(Scholarship scholarship,
			EssayQuestion question,
			List<AiInterview> history) {
		String systemPrompt = buildSystemPrompt(scholarship, question);
		String userMessage = buildUserMessage(history);
		return LlmChatRequest.of(LlmModel.DRAFT, systemPrompt, List.of(LlmMessage.user(userMessage)));
	}

	private String buildSystemPrompt(Scholarship scholarship, EssayQuestion question) {
		int limit = question.getCharLimit() != null ? question.getCharLimit() : DEFAULT_CHAR_LIMIT;
		return """
				당신은 대학생의 장학금 지원 자기소개서 초안을 작성하는 전문가입니다.

				<context>
				장학금: %s (%s)
				문항: %s
				문항 설명: %s
				글자수 제한: %d자 이내
				</context>

				작성 규칙:
				- 아래 사전 인터뷰 답변을 바탕으로, 학생의 목소리로 자연스럽게 서술하세요.
				- 인터뷰에 없는 사실을 만들어내지 마세요.
				- 구체적 경험·감정·성장을 중심으로 씁니다. 진부한 표현·과장은 지양.
				- 그대로 제출 가능한 완결된 자기소개서 본문이어야 합니다.
				- 글자수는 %d자를 넘지 마세요. 여백이 있으면 밀도있게 채우세요.
				- 응답에는 오직 자기소개서 본문만 포함하세요. 머리말·설명·마무리 인사 금지.
				""".formatted(
						nullSafe(scholarship.getTitle()),
						nullSafe(scholarship.getProvider()),
						nullSafe(question.getQuestionTitle()),
						nullSafe(question.getQuestionDescription()),
						limit,
						limit);
	}

	private String buildUserMessage(List<AiInterview> history) {
		StringBuilder sb = new StringBuilder("다음은 학생과의 사전 인터뷰 내용입니다.\n\n");
		for (AiInterview turn : history) {
			sb.append("Q: ").append(turn.getQuestionText()).append("\n");
			if (turn.getAnswerText() != null && !turn.getAnswerText().isBlank()) {
				sb.append("A: ").append(turn.getAnswerText()).append("\n\n");
			}
		}
		sb.append("위 내용을 바탕으로 자기소개서 초안을 작성해주세요.");
		return sb.toString();
	}

	private String nullSafe(String value) {
		return value == null ? "" : value;
	}
}
