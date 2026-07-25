package com.wishconnect.domain.application.service;

import com.wishconnect.domain.application.dto.response.AnswerDetailResponse;
import com.wishconnect.domain.application.dto.response.ApplicationDetailResponse;
import com.wishconnect.domain.application.dto.response.ApplicationListItemResponse;
import com.wishconnect.domain.application.dto.response.ApplicationListResponse;
import com.wishconnect.domain.application.dto.response.CreateApplicationResponse;
import com.wishconnect.domain.application.dto.response.InterviewTurnResponse;
import com.wishconnect.domain.application.dto.response.ProgressResponse;
import com.wishconnect.domain.application.dto.response.QuestionDetailResponse;
import com.wishconnect.domain.application.dto.response.QuestionStep;
import com.wishconnect.domain.application.entity.AiInterview;
import com.wishconnect.domain.application.entity.Essay;
import com.wishconnect.domain.application.entity.EssayAnswer;
import com.wishconnect.domain.application.entity.EssayQuestion;
import com.wishconnect.domain.application.entity.EssayStatus;
import com.wishconnect.domain.application.repository.AiInterviewRepository;
import com.wishconnect.domain.application.repository.EssayAnswerRepository;
import com.wishconnect.domain.application.repository.EssayQuestionRepository;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.notification.service.NotificationService;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자기소개서(지원서) 조회/생성/상세 서비스.
 * Notion API 명세서의 ①·②·③ 엔드포인트를 담당한다.
 * (STEP1 인터뷰·STEP2 답변 관리는 별도 서비스에서 처리)
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EssayApplicationService {

	private final EssayRepository essayRepository;
	private final EssayQuestionRepository essayQuestionRepository;
	private final EssayAnswerRepository essayAnswerRepository;
	private final AiInterviewRepository aiInterviewRepository;
	private final ScholarshipRepository scholarshipRepository;
	private final UserRepository userRepository;
	private final NotificationService notificationService;

	/**
	 * ① 지원서 목록 조회. status 로 필터링 가능.
	 */
	public ApplicationListResponse getApplications(UUID userId, EssayStatus statusFilter, Pageable pageable) {
		Page<Essay> page = statusFilter != null
				? essayRepository.findByUser_IdAndStatus(userId, statusFilter, pageable)
				: essayRepository.findByUser_Id(userId, pageable);

		List<ApplicationListItemResponse> items = page.getContent().stream()
				.map(this::toListItem)
				.toList();

		return new ApplicationListResponse(items, page.getTotalElements());
	}

	/**
	 * ③ 지원서 통합 상세 조회. 화면 진입 시 필요한 모든 데이터를 1회 호출로 반환한다.
	 * <p>
	 * 문항 수만큼의 개별 쿼리 대신, essay 단위로 question/answer/interview 를 각각 조회 후
	 * 메모리에서 매칭하여 문항별 응답을 구성한다 (N+1 방지).
	 */
	public ApplicationDetailResponse getApplicationDetail(UUID userId, Long applicationId) {
		Essay essay = essayRepository.findByIdAndUser_Id(applicationId, userId)
				.orElseThrow(() -> new CustomException(ErrorCode.APPLICATION_NOT_FOUND));

		List<EssayQuestion> questions = essayQuestionRepository
				.findByEssay_IdOrderByQuestionOrderAsc(applicationId);
		List<EssayAnswer> answers = essayAnswerRepository
				.findByEssayQuestion_Essay_Id(applicationId);
		List<AiInterview> interviews = aiInterviewRepository
				.findByEssayQuestion_Essay_IdOrderByEssayQuestion_IdAscStepOrderAsc(applicationId);

		Map<Long, EssayAnswer> answerByQuestionId = answers.stream()
				.collect(Collectors.toMap(a -> a.getEssayQuestion().getId(), Function.identity()));
		Map<Long, List<AiInterview>> interviewsByQuestionId = interviews.stream()
				.collect(Collectors.groupingBy(i -> i.getEssayQuestion().getId()));

		List<QuestionDetailResponse> questionResponses = questions.stream()
				.map(q -> toQuestionDetail(
						q,
						answerByQuestionId.get(q.getId()),
						interviewsByQuestionId.getOrDefault(q.getId(), List.of())))
				.toList();

		return new ApplicationDetailResponse(
				essay.getId(),
				essay.getScholarship().getTitle(),
				essay.getStatus(),
				essay.getLastEditedAt(),
				questionResponses);
	}

	private QuestionDetailResponse toQuestionDetail(EssayQuestion question,
			EssayAnswer answer,
			List<AiInterview> interviews) {
		List<InterviewTurnResponse> interviewResponses = interviews.stream()
				.map(i -> new InterviewTurnResponse(i.getStepOrder(), i.getQuestionText(), i.getAnswerText()))
				.toList();

		String seedQuestion = interviews.isEmpty() ? null : interviews.get(0).getQuestionText();
		AnswerDetailResponse answerResponse = answer == null ? null : new AnswerDetailResponse(
				answer.getAiDraft(),
				answer.getUserContent(),
				answer.getCharCount(),
				answer.isTemporary(),
				answer.isCompleted());

		return new QuestionDetailResponse(
				question.getId(),
				question.getQuestionOrder(),
				question.getQuestionTitle(),
				question.getQuestionDescription(),
				question.getCharLimit(),
				deriveCurrentStep(answer),
				seedQuestion,
				interviewResponses,
				answerResponse);
	}

	private QuestionStep deriveCurrentStep(EssayAnswer answer) {
		if (answer == null) {
			return QuestionStep.STEP_1;
		}
		if (answer.isCompleted()) {
			return QuestionStep.DONE;
		}
		if (answer.getAiDraft() != null && !answer.getAiDraft().isBlank()) {
			return QuestionStep.STEP_2;
		}
		return QuestionStep.STEP_1;
	}

	/**
	 * ② 지원서 작성 시작.
	 * essay + essay_question + 빈 essay_answer 를 트랜잭션 내에서 일괄 생성.
	 * (user, scholarship) 조합이 이미 존재하면 409.
	 * <p>
	 * TODO: 장학금별 문항 템플릿이 스키마에 정의되면 defaultEssayQuestions() 를 그 조회로 교체.
	 */
	@Transactional
	public CreateApplicationResponse createApplication(UUID userId, Long scholarshipId) {
		if (essayRepository.findByUser_IdAndScholarship_Id(userId, scholarshipId).isPresent()) {
			throw new CustomException(ErrorCode.APPLICATION_ALREADY_EXISTS);
		}

		Scholarship scholarship = scholarshipRepository.findById(scholarshipId)
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

		Essay essay = essayRepository.save(Essay.builder()
				.scholarship(scholarship)
				.user(user)
				.status(EssayStatus.NOT_STARTED)
				.build());

		List<EssayQuestion> questions = defaultEssayQuestions(essay);
		essayQuestionRepository.saveAll(questions);

		for (EssayQuestion question : questions) {
			essayAnswerRepository.save(EssayAnswer.builder()
					.essayQuestion(question)
					.charCount(0)
					.isTemporary(true)
					.isCompleted(false)
					.build());
		}
		createWritingNotificationSafely(essay);

		return new CreateApplicationResponse(essay.getId(), essay.getStatus(), questions.size());
	}

	private void createWritingNotificationSafely(Essay essay) {
		try {
			notificationService.createWritingNotification(essay);
		} catch (Exception e) {
			log.warn("지원서 작성 알림 생성 실패. essayId={}", essay.getId(), e);
		}
	}

	/**
	 * 임시 기본 문항 세트. 장학금별 문항 템플릿 스키마가 정의되기 전까지 사용한다.
	 */
	private List<EssayQuestion> defaultEssayQuestions(Essay essay) {
		return List.of(
				EssayQuestion.builder()
						.essay(essay)
						.questionOrder(1)
						.questionTitle("지원 동기")
						.questionDescription("이 장학금에 지원하게 된 계기와 이유를 서술해주세요.")
						.charLimit(500)
						.build(),
				EssayQuestion.builder()
						.essay(essay)
						.questionOrder(2)
						.questionTitle("성장 배경 및 자기소개")
						.questionDescription("본인의 성장 배경과 이를 통해 형성된 가치관을 서술해주세요.")
						.charLimit(800)
						.build()
		);
	}

	private ApplicationListItemResponse toListItem(Essay essay) {
		int total = (int) essayQuestionRepository.countByEssay_Id(essay.getId());
		int completed = (int) essayAnswerRepository.countByEssayQuestion_Essay_IdAndIsCompletedTrue(essay.getId());
		return new ApplicationListItemResponse(
				essay.getId(),
				essay.getScholarship().getId(),
				essay.getScholarship().getTitle(),
				essay.getStatus(),
				new ProgressResponse(completed, total),
				essay.getLastEditedAt()
		);
	}
}
