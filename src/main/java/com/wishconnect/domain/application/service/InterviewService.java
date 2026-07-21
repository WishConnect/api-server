package com.wishconnect.domain.application.service;

import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.dto.request.InterviewAnswerRequest;
import com.wishconnect.domain.application.dto.response.InterviewAdvanceResponse;
import com.wishconnect.domain.application.entity.AiInterview;
import com.wishconnect.domain.application.entity.Essay;
import com.wishconnect.domain.application.entity.EssayQuestion;
import com.wishconnect.domain.application.repository.AiInterviewRepository;
import com.wishconnect.domain.application.repository.EssayQuestionRepository;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.application.service.prompt.InterviewPromptBuilder;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STEP1 사전 인터뷰 대화 오케스트레이터.
 *
 * <p>Notion API 명세서 ④ POST /api/v1/applications/{id}/questions/{qid}/interview 를 담당한다.
 * 이력이 없는 경우 자동으로 부트스트랩(seed 질문 생성)하고, 이력이 있으면 사용자의 답변을
 * 반영한 뒤 다음 질문을 생성한다.
 *
 * <p>TODO: 트랜잭션 범위 안에서 LLM 호출을 수행하므로 커넥션 점유 시간이 길어질 수 있다.
 *   개선 시 조회/검증·LLM 호출·저장 3단계로 분리하고 저장만 짧은 트랜잭션으로 감쌀 것.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class InterviewService {

	private final EssayRepository essayRepository;
	private final EssayQuestionRepository essayQuestionRepository;
	private final AiInterviewRepository aiInterviewRepository;
	private final InterviewPromptBuilder promptBuilder;
	private final LlmClient llmClient;

	public InterviewAdvanceResponse advance(UUID userId,
			Long applicationId,
			Long questionId,
			InterviewAnswerRequest request) {

		Essay essay = essayRepository.findByIdAndUser_Id(applicationId, userId)
				.orElseThrow(() -> new CustomException(ErrorCode.APPLICATION_NOT_FOUND));
		EssayQuestion question = essayQuestionRepository.findByIdAndEssay_Id(questionId, applicationId)
				.orElseThrow(() -> new CustomException(ErrorCode.QUESTION_NOT_FOUND));

		List<AiInterview> history = aiInterviewRepository
				.findByEssayQuestion_IdOrderByStepOrderAsc(questionId);

		if (history.isEmpty()) {
			return bootstrap(essay, question);
		}
		return continueInterview(essay, question, history, request);
	}

	private InterviewAdvanceResponse bootstrap(Essay essay, EssayQuestion question) {
		LlmChatRequest chatRequest = promptBuilder.build(
				essay.getScholarship(), question, List.of());
		String response = llmClient.chat(chatRequest);

		// 극단적 예외 상황: 부트스트랩 즉시 완료 신호 → 저장 없이 완료 응답
		if (promptBuilder.isComplete(response)) {
			log.warn("부트스트랩 첫 호출에서 인터뷰 완료 신호가 반환됐습니다. essayId={}, questionId={}",
					essay.getId(), question.getId());
			return InterviewAdvanceResponse.complete();
		}

		AiInterview seed = aiInterviewRepository.save(AiInterview.builder()
				.essayQuestion(question)
				.questionText(response.trim())
				.stepOrder(0)
				.build());

		return InterviewAdvanceResponse.next(seed.getStepOrder(), seed.getQuestionText());
	}

	private InterviewAdvanceResponse continueInterview(Essay essay,
			EssayQuestion question,
			List<AiInterview> history,
			InterviewAnswerRequest request) {

		AiInterview pending = history.get(history.size() - 1);
		validateContinuation(pending, request);

		pending.recordAnswer(request.answerText());
		essay.markInProgress();

		int nextStepOrder = pending.getStepOrder() + 1;
		if (nextStepOrder >= InterviewPromptBuilder.MAX_TURNS) {
			return InterviewAdvanceResponse.complete();
		}

		LlmChatRequest chatRequest = promptBuilder.build(
				essay.getScholarship(), question, history);
		String response = llmClient.chat(chatRequest);

		if (promptBuilder.isComplete(response)) {
			return InterviewAdvanceResponse.complete();
		}

		AiInterview next = aiInterviewRepository.save(AiInterview.builder()
				.essayQuestion(question)
				.questionText(response.trim())
				.stepOrder(nextStepOrder)
				.build());

		return InterviewAdvanceResponse.next(next.getStepOrder(), next.getQuestionText());
	}

	private void validateContinuation(AiInterview pending, InterviewAnswerRequest request) {
		if (pending.getAnswerText() != null && !pending.getAnswerText().isBlank()) {
			// 이미 답변된 턴에 대해 다시 답변을 보낸 경우
			throw new CustomException(ErrorCode.INVALID_INTERVIEW_STEP);
		}
		if (request.stepOrder() == null || request.stepOrder() != pending.getStepOrder()) {
			throw new CustomException(ErrorCode.INVALID_INTERVIEW_STEP);
		}
		if (request.answerText() == null || request.answerText().isBlank()) {
			throw new CustomException(ErrorCode.INVALID_INTERVIEW_STEP);
		}
	}
}
