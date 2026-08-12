package com.wishconnect.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.application.dto.request.AnswerAction;
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
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.lang.reflect.Field;
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
class AnswerServiceTest {

	private static final UUID USER_ID = UUID.randomUUID();
	private static final Long APP_ID = 1L;
	private static final Long QUESTION_ID = 5L;

	@Mock private EssayRepository essayRepository;
	@Mock private EssayQuestionRepository essayQuestionRepository;
	@Mock private EssayAnswerRepository essayAnswerRepository;
	@Mock private AiInterviewRepository aiInterviewRepository;
	@Mock private DraftPromptBuilder draftPromptBuilder;
	@Mock private LlmClient llmClient;

	@InjectMocks private AnswerService answerService;

	// --- Fixture builders ---

	private Essay essay(EssayStatus status) {
		Essay essay = Essay.builder()
				.scholarship(scholarship())
				.status(status)
				.build();
		setField(essay, "id", APP_ID);
		return essay;
	}

	private Scholarship scholarship() {
		return Scholarship.builder().title("테스트 장학금").build();
	}

	private EssayQuestion question(Integer charLimit) {
		EssayQuestion q = EssayQuestion.builder()
				.questionOrder(1)
				.questionTitle("지원 동기")
				.questionDescription("이유를 서술하세요.")
				.charLimit(charLimit)
				.build();
		setField(q, "id", QUESTION_ID);
		return q;
	}

	private EssayAnswer answer(boolean completed) {
		return EssayAnswer.builder()
				.charCount(0)
				.isTemporary(!completed)
				.isCompleted(completed)
				.build();
	}

	private void stubLookup(Essay essay, EssayQuestion question, EssayAnswer answer) {
		given(essayRepository.findByIdAndUser_Id(APP_ID, USER_ID)).willReturn(Optional.of(essay));
		given(essayQuestionRepository.findByIdAndEssay_Id(QUESTION_ID, APP_ID))
				.willReturn(Optional.of(question));
		given(essayAnswerRepository.findByEssayQuestion_Id(QUESTION_ID))
				.willReturn(Optional.of(answer));
	}

	private LlmChatRequest dummyRequest() {
		return LlmChatRequest.of(LlmModel.DRAFT, "sys", List.of(LlmMessage.user("prompt")));
	}

	// --- DRAFT ---

	@Test
	@DisplayName("DRAFT: 인터뷰 이력이 비어있으면 INTERVIEW_NOT_STARTED 로 실패")
	void draft_noInterview_throws() {
		stubLookup(essay(EssayStatus.NOT_STARTED), question(500), answer(false));
		given(aiInterviewRepository.findByEssayQuestion_IdOrderByStepOrderAsc(QUESTION_ID))
				.willReturn(List.of());

		assertThatThrownBy(() -> answerService.handle(
				USER_ID, APP_ID, QUESTION_ID,
				new AnswerActionRequest(AnswerAction.DRAFT, null)))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INTERVIEW_NOT_STARTED);
	}

	@Test
	@DisplayName("DRAFT: 질문만 생성되고 답변이 하나도 없으면 INTERVIEW_NOT_STARTED 로 실패")
	void draft_questionsWithoutAnswers_throws() {
		stubLookup(essay(EssayStatus.NOT_STARTED), question(500), answer(false));
		given(aiInterviewRepository.findByEssayQuestion_IdOrderByStepOrderAsc(QUESTION_ID))
				.willReturn(List.of(
						AiInterview.builder().questionText("질문0").stepOrder(0).build(),
						AiInterview.builder().questionText("질문1").stepOrder(1).build()));

		assertThatThrownBy(() -> answerService.handle(
				USER_ID, APP_ID, QUESTION_ID,
				new AnswerActionRequest(AnswerAction.DRAFT, null)))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INTERVIEW_NOT_STARTED);

		verify(llmClient, never()).chat(any());
	}

	@Test
	@DisplayName("DRAFT: 인터뷰 이력이 있으면 LLM 호출 후 answer 에 초안이 반영된다")
	void draft_success() {
		Essay essay = essay(EssayStatus.IN_PROGRESS);
		EssayQuestion question = question(500);
		EssayAnswer answer = answer(false);
		stubLookup(essay, question, answer);
		given(aiInterviewRepository.findByEssayQuestion_IdOrderByStepOrderAsc(QUESTION_ID))
				.willReturn(List.of(mockInterview()));
		given(draftPromptBuilder.build(any(), any(), any())).willReturn(dummyRequest());
		given(llmClient.chat(any())).willReturn("AI 가 생성한 자기소개서 초안입니다.");

		AnswerActionResponse response = answerService.handle(
				USER_ID, APP_ID, QUESTION_ID,
				new AnswerActionRequest(AnswerAction.DRAFT, null));

		verify(llmClient).chat(any());
		assertThat(answer.getAiDraft()).isEqualTo("AI 가 생성한 자기소개서 초안입니다.");
		assertThat(answer.getUserContent()).isEqualTo("AI 가 생성한 자기소개서 초안입니다.");
		assertThat(response.isCompleted()).isFalse();
		assertThat(response.applicationCompleted()).isFalse();
	}

	// --- SAVE ---

	@Test
	@DisplayName("SAVE: userContent 가 null 이면 ANSWER_CONTENT_REQUIRED 로 실패")
	void save_nullContent_throws() {
		stubLookup(essay(EssayStatus.IN_PROGRESS), question(500), answer(false));

		assertThatThrownBy(() -> answerService.handle(
				USER_ID, APP_ID, QUESTION_ID,
				new AnswerActionRequest(AnswerAction.SAVE, null)))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANSWER_CONTENT_REQUIRED);
	}

	@Test
	@DisplayName("SAVE: blank(빈 문자열) 은 임시저장 특성상 허용된다")
	void save_blankContent_allowed() {
		Essay essay = essay(EssayStatus.IN_PROGRESS);
		EssayAnswer answer = answer(false);
		stubLookup(essay, question(500), answer);

		AnswerActionResponse response = answerService.handle(
				USER_ID, APP_ID, QUESTION_ID,
				new AnswerActionRequest(AnswerAction.SAVE, ""));

		assertThat(answer.getUserContent()).isEqualTo("");
		assertThat(response.isCompleted()).isFalse();
	}

	// --- CONFIRM ---

	@Test
	@DisplayName("CONFIRM: blank 는 ANSWER_CONTENT_REQUIRED 로 실패")
	void confirm_blank_throws() {
		stubLookup(essay(EssayStatus.IN_PROGRESS), question(500), answer(false));

		assertThatThrownBy(() -> answerService.handle(
				USER_ID, APP_ID, QUESTION_ID,
				new AnswerActionRequest(AnswerAction.CONFIRM, "   ")))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANSWER_CONTENT_REQUIRED);
	}

	@Test
	@DisplayName("CONFIRM: charLimit 초과 시 ANSWER_EXCEEDS_CHAR_LIMIT 로 실패")
	void confirm_exceedsLimit_throws() {
		stubLookup(essay(EssayStatus.IN_PROGRESS), question(10), answer(false));

		assertThatThrownBy(() -> answerService.handle(
				USER_ID, APP_ID, QUESTION_ID,
				new AnswerActionRequest(AnswerAction.CONFIRM, "이것은 열자를 넘는 답변입니다")))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANSWER_EXCEEDS_CHAR_LIMIT);
	}

	@Test
	@DisplayName("CONFIRM: 남은 미완료 문항이 있으면 essay 는 IN_PROGRESS 유지, applicationCompleted=false")
	void confirm_notLastQuestion() {
		Essay essay = essay(EssayStatus.IN_PROGRESS);
		EssayAnswer answer = answer(false);
		stubLookup(essay, question(500), answer);
		// 전체 3문항, 확정된 문항 2 (이번 CONFIRM 포함해도 3 미달)
		given(essayQuestionRepository.countByEssay_Id(APP_ID)).willReturn(3L);
		given(essayAnswerRepository.countByEssayQuestion_Essay_IdAndIsCompletedTrue(APP_ID))
				.willReturn(2L);

		AnswerActionResponse response = answerService.handle(
				USER_ID, APP_ID, QUESTION_ID,
				new AnswerActionRequest(AnswerAction.CONFIRM, "본문 내용"));

		assertThat(response.isCompleted()).isTrue();
		assertThat(response.applicationCompleted()).isFalse();
		assertThat(essay.getStatus()).isEqualTo(EssayStatus.IN_PROGRESS);
	}

	@Test
	@DisplayName("CONFIRM: 마지막 미완료 문항이면 essay 를 COMPLETED 로 전환하고 applicationCompleted=true")
	void confirm_lastQuestion_transitionsCompleted() {
		Essay essay = essay(EssayStatus.IN_PROGRESS);
		EssayAnswer answer = answer(false);
		stubLookup(essay, question(500), answer);
		given(essayQuestionRepository.countByEssay_Id(APP_ID)).willReturn(2L);
		given(essayAnswerRepository.countByEssayQuestion_Essay_IdAndIsCompletedTrue(APP_ID))
				.willReturn(2L);

		AnswerActionResponse response = answerService.handle(
				USER_ID, APP_ID, QUESTION_ID,
				new AnswerActionRequest(AnswerAction.CONFIRM, "본문"));

		assertThat(response.isCompleted()).isTrue();
		assertThat(response.applicationCompleted()).isTrue();
		assertThat(essay.getStatus()).isEqualTo(EssayStatus.COMPLETED);
	}

	@Test
	@DisplayName("CONFIRM 재확정: 이미 COMPLETED 인 지원서의 문항을 다시 확정해도 applicationCompleted=false")
	void confirm_alreadyCompleted_returnsFalse() {
		Essay essay = essay(EssayStatus.COMPLETED);
		EssayAnswer answer = answer(true);
		stubLookup(essay, question(500), answer);
		// 재확정 후에도 전 문항 완료 상태 유지
		given(essayQuestionRepository.countByEssay_Id(APP_ID)).willReturn(2L);
		given(essayAnswerRepository.countByEssayQuestion_Essay_IdAndIsCompletedTrue(APP_ID))
				.willReturn(2L);

		AnswerActionResponse response = answerService.handle(
				USER_ID, APP_ID, QUESTION_ID,
				new AnswerActionRequest(AnswerAction.CONFIRM, "수정된 본문"));

		assertThat(response.isCompleted()).isTrue();
		// 이미 완료된 상태에서의 재확정이므로 "이번 호출로 전환"은 아님
		assertThat(response.applicationCompleted()).isFalse();
		// checkAndCompleteEssay 가 다시 COMPLETED 로 만들었으므로 상태는 COMPLETED 유지
		assertThat(essay.getStatus()).isEqualTo(EssayStatus.COMPLETED);
	}

	@Test
	@DisplayName("DRAFT/SAVE 재편집: COMPLETED 지원서의 편집도 essay 를 IN_PROGRESS 로 되돌린다 (옵션 B)")
	void draft_onCompletedEssay_reopensToInProgress() {
		Essay essay = essay(EssayStatus.COMPLETED);
		EssayAnswer answer = answer(true);
		stubLookup(essay, question(500), answer);
		given(aiInterviewRepository.findByEssayQuestion_IdOrderByStepOrderAsc(QUESTION_ID))
				.willReturn(List.of(mockInterview()));
		given(draftPromptBuilder.build(any(), any(), any())).willReturn(dummyRequest());
		given(llmClient.chat(any())).willReturn("새 초안");

		answerService.handle(
				USER_ID, APP_ID, QUESTION_ID,
				new AnswerActionRequest(AnswerAction.DRAFT, null));

		assertThat(essay.getStatus()).isEqualTo(EssayStatus.IN_PROGRESS);
	}

	// --- 공통 오류 ---

	@Test
	@DisplayName("지원서가 없거나 다른 사용자 소유면 APPLICATION_NOT_FOUND")
	void applicationNotFound() {
		given(essayRepository.findByIdAndUser_Id(APP_ID, USER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> answerService.handle(
				USER_ID, APP_ID, QUESTION_ID,
				new AnswerActionRequest(AnswerAction.SAVE, "본문")))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.APPLICATION_NOT_FOUND);

		verify(llmClient, never()).chat(any());
	}

	// --- helpers ---

	private AiInterview mockInterview() {
		return AiInterview.builder()
				.questionText("첫 질문")
				.answerText("첫 답변")
				.stepOrder(0)
				.build();
	}

	/** 리플렉션으로 엔티티 id 등을 강제 세팅 (레포지토리 매칭용). */
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
