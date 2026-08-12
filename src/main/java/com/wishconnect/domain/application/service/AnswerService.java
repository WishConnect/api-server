package com.wishconnect.domain.application.service;

import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.dto.request.AnswerActionRequest;
import com.wishconnect.domain.application.dto.response.AnswerActionResponse;
import com.wishconnect.domain.application.entity.AiInterview;
import com.wishconnect.domain.application.entity.Essay;
import com.wishconnect.domain.application.entity.EssayAnswer;
import com.wishconnect.domain.application.entity.EssayQuestion;
import com.wishconnect.domain.application.entity.EssayStatus;
import com.wishconnect.domain.application.repository.AiInterviewRepository;
import com.wishconnect.domain.application.repository.EssayAnswerRepository;
import com.wishconnect.domain.application.repository.EssayQuestionRepository;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.application.service.prompt.DraftPromptBuilder;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STEP2 답변 관리 오케스트레이터.
 *
 * <p>Notion API 명세서 ⑤ PUT /api/v1/applications/{id}/questions/{qid}/answer 를 담당한다.
 * action 파라미터로 세 동작을 하나에 통합:
 * <ul>
 *   <li>DRAFT — 인터뷰 이력으로 LLM(Sonnet) 초안 생성, essay_answer.ai_draft/user_content 갱신</li>
 *   <li>SAVE  — 사용자 편집 본문 임시저장</li>
 *   <li>CONFIRM — 완료 확정 (글자수 검증). 전 문항 완료 시 essay 자동 COMPLETED</li>
 * </ul>
 *
 * <p>TODO: DRAFT 액션은 트랜잭션 안에서 LLM 호출을 수행해 DB 커넥션 점유 시간이 길다.
 *   개선 시 조회·검증/LLM 호출/저장 3단계로 분리 필요 (InterviewService 와 동일).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AnswerService {

	private final EssayRepository essayRepository;
	private final EssayQuestionRepository essayQuestionRepository;
	private final EssayAnswerRepository essayAnswerRepository;
	private final AiInterviewRepository aiInterviewRepository;
	private final DraftPromptBuilder draftPromptBuilder;
	private final LlmClient llmClient;

	public AnswerActionResponse handle(UUID userId,
			Long applicationId,
			Long questionId,
			AnswerActionRequest request) {

		Essay essay = essayRepository.findByIdAndUser_Id(applicationId, userId)
				.orElseThrow(() -> new CustomException(ErrorCode.APPLICATION_NOT_FOUND));
		EssayQuestion question = essayQuestionRepository.findByIdAndEssay_Id(questionId, applicationId)
				.orElseThrow(() -> new CustomException(ErrorCode.QUESTION_NOT_FOUND));
		EssayAnswer answer = essayAnswerRepository.findByEssayQuestion_Id(questionId)
				.orElseThrow(() -> new CustomException(ErrorCode.ANSWER_NOT_FOUND));

		return switch (request.action()) {
			case DRAFT -> handleDraft(essay, question, answer);
			case SAVE -> handleSave(essay, question, answer, request);
			case CONFIRM -> handleConfirm(essay, question, answer, request);
		};
	}

	private AnswerActionResponse handleDraft(Essay essay, EssayQuestion question, EssayAnswer answer) {
		List<AiInterview> history = aiInterviewRepository
				.findByEssayQuestion_IdOrderByStepOrderAsc(question.getId());
		// 질문 일괄 생성 방식에서는 답변 전에도 이력이 존재한다. 답변이 1건도 없으면 초안 재료가 없는 셈.
		if (history.stream().noneMatch(AiInterview::isAnswered)) {
			throw new CustomException(ErrorCode.INTERVIEW_NOT_STARTED);
		}

		LlmChatRequest chatRequest = draftPromptBuilder.build(
				essay.getScholarship(), question, history);
		String draft = llmClient.chat(chatRequest);

		answer.applyDraft(draft.trim());
		essay.markInProgress();

		// LLM 이 프롬프트의 글자수 제약을 어겨 초과 생성할 수 있다. 사용자는 CONFIRM 단계에서만
		// 400 을 만나 원인 파악이 어려우므로 DRAFT 시점에 미리 로그로 남긴다.
		if (question.getCharLimit() != null && answer.getCharCount() > question.getCharLimit()) {
			log.warn("AI 초안이 문항 글자수 제한을 초과했습니다. "
					+ "questionId={}, charCount={}, charLimit={}",
					question.getId(), answer.getCharCount(), question.getCharLimit());
		}

		return new AnswerActionResponse(
				question.getId(),
				answer.getCharCount(),
				question.getCharLimit(),
				false,
				false);
	}

	private AnswerActionResponse handleSave(Essay essay,
			EssayQuestion question,
			EssayAnswer answer,
			AnswerActionRequest request) {
		// 임시저장은 사용자가 방금 본문을 지운 상태(빈 문자열)도 그대로 보존해야 하므로
		// blank 는 허용한다. null 만 방어하고, 빈 문자열/공백은 그대로 저장.
		// (CONFIRM 은 최종 제출이라 blank 를 별도로 거부한다)
		if (request.userContent() == null) {
			throw new CustomException(ErrorCode.ANSWER_CONTENT_REQUIRED);
		}

		answer.updateUserContent(request.userContent());
		essay.markInProgress();

		return new AnswerActionResponse(
				question.getId(),
				answer.getCharCount(),
				question.getCharLimit(),
				false,
				false);
	}

	private AnswerActionResponse handleConfirm(Essay essay,
			EssayQuestion question,
			EssayAnswer answer,
			AnswerActionRequest request) {
		String content = request.userContent();
		if (content == null || content.isBlank()) {
			throw new CustomException(ErrorCode.ANSWER_CONTENT_REQUIRED);
		}
		if (question.getCharLimit() != null && content.length() > question.getCharLimit()) {
			throw new CustomException(ErrorCode.ANSWER_EXCEEDS_CHAR_LIMIT);
		}

		// markInProgress 가 COMPLETED 를 IN_PROGRESS 로 되돌리기 전 상태 캡처.
		// 이미 COMPLETED 였다면 재확정에 해당하므로 applicationCompleted=false 로 반환한다.
		boolean wasAlreadyCompleted = essay.getStatus() == EssayStatus.COMPLETED;

		answer.confirm(content);
		essay.markInProgress();

		boolean nowCompleted = checkAndCompleteEssay(essay);
		boolean applicationCompleted = nowCompleted && !wasAlreadyCompleted;

		return new AnswerActionResponse(
				question.getId(),
				answer.getCharCount(),
				question.getCharLimit(),
				true,
				applicationCompleted);
	}

	/**
	 * 지원서의 전 문항이 완료 확정 상태이면 essay 를 COMPLETED 로 전환한다.
	 *
	 * @return true 이면 이번 호출로 COMPLETED 전환이 발생함
	 */
	private boolean checkAndCompleteEssay(Essay essay) {
		long total = essayQuestionRepository.countByEssay_Id(essay.getId());
		long completed = essayAnswerRepository.countByEssayQuestion_Essay_IdAndIsCompletedTrue(essay.getId());
		if (total > 0 && total == completed) {
			essay.markCompleted();
			return true;
		}
		return false;
	}
}
