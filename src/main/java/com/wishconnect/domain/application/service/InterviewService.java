package com.wishconnect.domain.application.service;

import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.dto.request.InterviewAnswerItem;
import com.wishconnect.domain.application.dto.request.InterviewAnswerRequest;
import com.wishconnect.domain.application.dto.response.InterviewAdvanceResponse;
import com.wishconnect.domain.application.dto.response.InterviewTurnResponse;
import com.wishconnect.domain.application.entity.AiInterview;
import com.wishconnect.domain.application.entity.Essay;
import com.wishconnect.domain.application.entity.EssayQuestion;
import com.wishconnect.domain.application.repository.AiInterviewRepository;
import com.wishconnect.domain.application.repository.EssayQuestionRepository;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.notification.service.NotificationService;
import com.wishconnect.domain.application.service.prompt.InterviewPromptBuilder;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STEP1 사전 인터뷰 오케스트레이터.
 *
 * <p>Notion API 명세서 ④ POST /api/v1/applications/{id}/questions/{qid}/interview 를 담당한다.
 * 이력이 없으면 문항(카테고리)별 사전 질문을 <b>한 번에 전부 생성</b>하고, 이력이 있으면 요청에 담긴
 * 답변들을 저장한다. 어느 경우든 질문 전체와 현재 답변 상태를 함께 반환한다.
 *
 * <p>질문을 일괄 생성하므로 문항당 LLM 호출은 1회다. 답변 저장에는 LLM 호출이 없다.
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
	private final NotificationService notificationService;

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
			return generateQuestions(essay, question);
		}
		return recordAnswers(essay, history, request);
	}

	/**
	 * 문항의 사전 질문을 LLM 으로 일괄 생성해 저장한다. stepOrder 는 0 부터 순서대로 부여한다.
	 */
	private InterviewAdvanceResponse generateQuestions(Essay essay, EssayQuestion question) {
		LlmChatRequest chatRequest = promptBuilder.build(essay.getScholarship(), question);
		List<String> questionTexts = promptBuilder.parseQuestions(llmClient.chat(chatRequest));

		if (questionTexts.isEmpty()) {
			log.warn("사전 질문 생성 결과가 비어 있습니다. essayId={}, questionId={}",
					essay.getId(), question.getId());
			throw new CustomException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED);
		}
		if (questionTexts.size() < InterviewPromptBuilder.QUESTION_COUNT) {
			// 질문이 모자라도 인터뷰 자체는 진행 가능하므로 막지 않고 기록만 남긴다.
			log.warn("사전 질문이 요청 개수보다 적게 생성됐습니다. essayId={}, questionId={}, 생성={}, 요청={}",
					essay.getId(), question.getId(), questionTexts.size(),
					InterviewPromptBuilder.QUESTION_COUNT);
		}

		List<AiInterview> created = new ArrayList<>();
		for (int stepOrder = 0; stepOrder < questionTexts.size(); stepOrder++) {
			created.add(AiInterview.builder()
					.essayQuestion(question)
					.questionText(questionTexts.get(stepOrder))
					.stepOrder(stepOrder)
					.build());
		}
		aiInterviewRepository.saveAll(created);

		return toResponse(created);
	}

	/**
	 * 요청에 담긴 답변들을 저장한다. 부분 제출을 허용하므로 일부만 보내도 되고,
	 * 이미 답변한 stepOrder 를 다시 보내면 덮어쓴다.
	 *
	 * <p>답변이 하나도 담기지 않은 요청(빈 body 재호출 포함)은 오류가 아니라 현재 상태 조회로 취급한다.
	 */
	private InterviewAdvanceResponse recordAnswers(Essay essay,
			List<AiInterview> history,
			InterviewAnswerRequest request) {

		List<InterviewAnswerItem> items = request.safeAnswers();
		Map<Integer, AiInterview> byStepOrder = history.stream()
				.collect(Collectors.toMap(AiInterview::getStepOrder, Function.identity()));

		Set<Integer> seen = new HashSet<>();
		boolean anySaved = false;

		for (InterviewAnswerItem item : items) {
			AiInterview target = resolveTarget(byStepOrder, seen, item);
			if (item.answerText() == null || item.answerText().isBlank()) {
				// 아직 작성하지 않은 항목을 클라이언트가 함께 보낸 경우. 기존 답변을 지우지 않고 건너뛴다.
				continue;
			}
			target.writeAnswer(item.answerText());
			anySaved = true;
		}

		if (anySaved) {
			essay.markInProgress();
			createWritingNotificationSafely(essay);
		}

		return toResponse(history);
	}

	/**
	 * stepOrder 로 저장 대상 인터뷰를 찾고 요청 자체의 정합성을 검증한다.
	 * stepOrder 누락·미존재·중복은 클라이언트 버그이므로 400 으로 막는다.
	 */
	private AiInterview resolveTarget(Map<Integer, AiInterview> byStepOrder,
			Set<Integer> seen,
			InterviewAnswerItem item) {

		if (item.stepOrder() == null) {
			throw new CustomException(ErrorCode.INVALID_INTERVIEW_STEP);
		}
		if (!seen.add(item.stepOrder())) {
			throw new CustomException(ErrorCode.INVALID_INTERVIEW_STEP);
		}
		AiInterview target = byStepOrder.get(item.stepOrder());
		if (target == null) {
			throw new CustomException(ErrorCode.INVALID_INTERVIEW_STEP);
		}
		return target;
	}

	private InterviewAdvanceResponse toResponse(List<AiInterview> interviews) {
		return InterviewAdvanceResponse.of(interviews.stream()
				.map(i -> new InterviewTurnResponse(i.getStepOrder(), i.getQuestionText(), i.getAnswerText()))
				.toList());
	}

	private void createWritingNotificationSafely(Essay essay) {
		try {
			notificationService.createWritingNotification(essay);
		} catch (Exception e) {
			log.warn("인터뷰 작성 알림 생성 실패. essayId={}", essay.getId(), e);
		}
	}
}
