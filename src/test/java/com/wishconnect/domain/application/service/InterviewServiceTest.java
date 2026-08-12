package com.wishconnect.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.application.dto.request.InterviewAnswerItem;
import com.wishconnect.domain.application.dto.request.InterviewAnswerRequest;
import com.wishconnect.domain.application.dto.response.InterviewAdvanceResponse;
import com.wishconnect.domain.application.entity.AiInterview;
import com.wishconnect.domain.application.entity.Essay;
import com.wishconnect.domain.application.entity.EssayQuestion;
import com.wishconnect.domain.application.entity.EssayStatus;
import com.wishconnect.domain.application.repository.AiInterviewRepository;
import com.wishconnect.domain.application.repository.EssayQuestionRepository;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.application.service.prompt.InterviewPromptBuilder;
import com.wishconnect.domain.notification.service.NotificationService;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InterviewServiceTest {

	private static final UUID USER_ID = UUID.randomUUID();
	private static final Long APP_ID = 1L;
	private static final Long QUESTION_ID = 5L;

	@Mock private EssayRepository essayRepository;
	@Mock private EssayQuestionRepository essayQuestionRepository;
	@Mock private AiInterviewRepository aiInterviewRepository;
	@Mock private InterviewPromptBuilder promptBuilder;
	@Mock private LlmClient llmClient;
	@Mock private NotificationService notificationService;

	@InjectMocks private InterviewService interviewService;

	// --- Fixture builders ---

	private Essay essay(EssayStatus status) {
		Essay essay = Essay.builder()
				.scholarship(Scholarship.builder().title("테스트 장학금").build())
				.status(status)
				.build();
		setField(essay, "id", APP_ID);
		return essay;
	}

	private EssayQuestion question() {
		EssayQuestion question = EssayQuestion.builder()
				.questionOrder(1)
				.questionTitle("지원 동기")
				.questionDescription("지원 계기를 서술해주세요.")
				.charLimit(500)
				.build();
		setField(question, "id", QUESTION_ID);
		return question;
	}

	private AiInterview interview(int stepOrder, String answerText) {
		return AiInterview.builder()
				.questionText("질문 " + stepOrder)
				.answerText(answerText)
				.stepOrder(stepOrder)
				.build();
	}

	/** stepOrder 0..count-1 의 미답변 질문 목록. 서비스가 답변을 써넣으므로 가변 리스트로 만든다. */
	private List<AiInterview> unansweredHistory(int count) {
		List<AiInterview> history = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			history.add(interview(i, null));
		}
		return history;
	}

	private void stubLookup(Essay essay, EssayQuestion question) {
		given(essayRepository.findByIdAndUser_Id(APP_ID, USER_ID)).willReturn(Optional.of(essay));
		given(essayQuestionRepository.findByIdAndEssay_Id(QUESTION_ID, APP_ID))
				.willReturn(Optional.of(question));
	}

	private InterviewAdvanceResponse advance(InterviewAnswerRequest request) {
		return interviewService.advance(USER_ID, APP_ID, QUESTION_ID, request);
	}

	private LlmChatRequest dummyRequest() {
		return LlmChatRequest.of(LlmModel.INTERVIEW, "sys", List.of(LlmMessage.user("prompt")));
	}

	// --- 질문 일괄 생성 ---

	@Test
	@DisplayName("이력이 없으면 사전 질문 5개를 LLM 1회 호출로 생성해 전부 반환한다")
	void generateQuestions_createsAllAtOnce() {
		stubLookup(essay(EssayStatus.NOT_STARTED), question());
		given(aiInterviewRepository.findByEssayQuestion_IdOrderByStepOrderAsc(QUESTION_ID))
				.willReturn(List.of());
		given(promptBuilder.build(any(), any())).willReturn(dummyRequest());
		given(llmClient.chat(any())).willReturn("무관한 원문");
		given(promptBuilder.parseQuestions("무관한 원문"))
				.willReturn(List.of("질문1", "질문2", "질문3", "질문4", "질문5"));

		InterviewAdvanceResponse response = advance(new InterviewAnswerRequest(null));

		assertThat(response.questions()).hasSize(5);
		assertThat(response.questions()).extracting("stepOrder").containsExactly(0, 1, 2, 3, 4);
		assertThat(response.questions()).extracting("questionText")
				.containsExactly("질문1", "질문2", "질문3", "질문4", "질문5");
		assertThat(response.totalCount()).isEqualTo(5);
		assertThat(response.answeredCount()).isZero();
		assertThat(response.isInterviewComplete()).isFalse();
		assertThat(response.canGenerateDraft()).isFalse();

		verify(llmClient).chat(any());
		verify(aiInterviewRepository).saveAll(anyList());
	}

	@Test
	@DisplayName("LLM 이 질문을 하나도 만들지 못하면 INTERVIEW_QUESTION_GENERATION_FAILED 로 실패")
	void generateQuestions_empty_throws() {
		stubLookup(essay(EssayStatus.NOT_STARTED), question());
		given(aiInterviewRepository.findByEssayQuestion_IdOrderByStepOrderAsc(QUESTION_ID))
				.willReturn(List.of());
		given(promptBuilder.build(any(), any())).willReturn(dummyRequest());
		given(llmClient.chat(any())).willReturn("");
		given(promptBuilder.parseQuestions("")).willReturn(List.of());

		assertThatThrownBy(() -> advance(new InterviewAnswerRequest(null)))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED);

		verify(aiInterviewRepository, never()).saveAll(anyList());
	}

	// --- 답변 저장 ---

	@Test
	@DisplayName("답변을 일괄 제출하면 전부 저장되고 인터뷰가 완료 처리된다")
	void recordAnswers_allAtOnce_completes() {
		Essay essay = essay(EssayStatus.NOT_STARTED);
		stubLookup(essay, question());
		List<AiInterview> history = unansweredHistory(5);
		given(aiInterviewRepository.findByEssayQuestion_IdOrderByStepOrderAsc(QUESTION_ID))
				.willReturn(history);

		List<InterviewAnswerItem> answers = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			answers.add(new InterviewAnswerItem(i, "답변" + i));
		}

		InterviewAdvanceResponse response = advance(new InterviewAnswerRequest(answers));

		assertThat(response.answeredCount()).isEqualTo(5);
		assertThat(response.isInterviewComplete()).isTrue();
		assertThat(response.canGenerateDraft()).isTrue();
		assertThat(response.questions()).extracting("answerText")
				.containsExactly("답변0", "답변1", "답변2", "답변3", "답변4");
		assertThat(essay.getStatus()).isEqualTo(EssayStatus.IN_PROGRESS);
		// 답변 저장 단계에서는 LLM 을 호출하지 않는다.
		verify(llmClient, never()).chat(any());
	}

	@Test
	@DisplayName("일부만 제출해도 저장되며 1건 이상이면 초안 생성이 가능해진다")
	void recordAnswers_partial_allowsDraft() {
		stubLookup(essay(EssayStatus.NOT_STARTED), question());
		given(aiInterviewRepository.findByEssayQuestion_IdOrderByStepOrderAsc(QUESTION_ID))
				.willReturn(unansweredHistory(5));

		InterviewAdvanceResponse response = advance(new InterviewAnswerRequest(
				List.of(new InterviewAnswerItem(2, "세 번째 답변"))));

		assertThat(response.answeredCount()).isEqualTo(1);
		assertThat(response.isInterviewComplete()).isFalse();
		assertThat(response.canGenerateDraft()).isTrue();
		assertThat(response.questions().get(2).answerText()).isEqualTo("세 번째 답변");
	}

	@Test
	@DisplayName("이미 답변한 stepOrder 를 다시 제출하면 덮어쓴다")
	void recordAnswers_resubmit_overwrites() {
		stubLookup(essay(EssayStatus.NOT_STARTED), question());
		List<AiInterview> history = List.of(interview(0, "예전 답변"), interview(1, null));
		given(aiInterviewRepository.findByEssayQuestion_IdOrderByStepOrderAsc(QUESTION_ID))
				.willReturn(history);

		InterviewAdvanceResponse response = advance(new InterviewAnswerRequest(
				List.of(new InterviewAnswerItem(0, "수정한 답변"))));

		assertThat(response.questions().get(0).answerText()).isEqualTo("수정한 답변");
	}

	@Test
	@DisplayName("빈 답변 항목은 기존 답변을 지우지 않고 건너뛴다")
	void recordAnswers_blank_isSkipped() {
		stubLookup(essay(EssayStatus.NOT_STARTED), question());
		given(aiInterviewRepository.findByEssayQuestion_IdOrderByStepOrderAsc(QUESTION_ID))
				.willReturn(List.of(interview(0, "기존 답변"), interview(1, null)));

		InterviewAdvanceResponse response = advance(new InterviewAnswerRequest(
				List.of(new InterviewAnswerItem(0, "   "), new InterviewAnswerItem(1, null))));

		assertThat(response.questions().get(0).answerText()).isEqualTo("기존 답변");
		assertThat(response.questions().get(1).answerText()).isNull();
		assertThat(response.answeredCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("빈 body 로 다시 호출하면 오류 없이 현재 상태를 그대로 반환한다")
	void recordAnswers_emptyBody_returnsCurrentState() {
		stubLookup(essay(EssayStatus.NOT_STARTED), question());
		given(aiInterviewRepository.findByEssayQuestion_IdOrderByStepOrderAsc(QUESTION_ID))
				.willReturn(List.of(interview(0, "답변"), interview(1, null)));

		InterviewAdvanceResponse response = advance(new InterviewAnswerRequest(null));

		assertThat(response.totalCount()).isEqualTo(2);
		assertThat(response.answeredCount()).isEqualTo(1);
		verify(llmClient, never()).chat(any());
	}

	// --- 요청 정합성 검증 ---

	@Test
	@DisplayName("존재하지 않는 stepOrder 를 보내면 INVALID_INTERVIEW_STEP 로 실패")
	void recordAnswers_unknownStepOrder_throws() {
		stubLookup(essay(EssayStatus.NOT_STARTED), question());
		given(aiInterviewRepository.findByEssayQuestion_IdOrderByStepOrderAsc(QUESTION_ID))
				.willReturn(unansweredHistory(5));

		assertThatThrownBy(() -> advance(new InterviewAnswerRequest(
				List.of(new InterviewAnswerItem(9, "답변")))))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INTERVIEW_STEP);
	}

	@Test
	@DisplayName("stepOrder 가 null 이면 INVALID_INTERVIEW_STEP 로 실패")
	void recordAnswers_nullStepOrder_throws() {
		stubLookup(essay(EssayStatus.NOT_STARTED), question());
		given(aiInterviewRepository.findByEssayQuestion_IdOrderByStepOrderAsc(QUESTION_ID))
				.willReturn(unansweredHistory(5));

		assertThatThrownBy(() -> advance(new InterviewAnswerRequest(
				List.of(new InterviewAnswerItem(null, "답변")))))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INTERVIEW_STEP);
	}

	@Test
	@DisplayName("한 요청에 같은 stepOrder 가 중복되면 INVALID_INTERVIEW_STEP 로 실패")
	void recordAnswers_duplicateStepOrder_throws() {
		stubLookup(essay(EssayStatus.NOT_STARTED), question());
		given(aiInterviewRepository.findByEssayQuestion_IdOrderByStepOrderAsc(QUESTION_ID))
				.willReturn(unansweredHistory(5));

		assertThatThrownBy(() -> advance(new InterviewAnswerRequest(List.of(
				new InterviewAnswerItem(0, "답변 A"),
				new InterviewAnswerItem(0, "답변 B")))))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INTERVIEW_STEP);
	}

	// --- Reflection helper (엔티티 ID 는 setter 가 없어 리플렉션으로 주입) ---

	private static void setField(Object target, String fieldName, Object value) {
		try {
			Field f = findField(target.getClass(), fieldName);
			f.setAccessible(true);
			f.set(target, value);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
		Class<?> c = clazz;
		while (c != null && c != Object.class) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}
}
