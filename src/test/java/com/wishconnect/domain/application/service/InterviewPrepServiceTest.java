package com.wishconnect.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.application.dto.response.InterviewPrepResponse;
import com.wishconnect.domain.application.entity.InterviewPrepQuestion;
import com.wishconnect.domain.application.repository.InterviewPrepQuestionRepository;
import com.wishconnect.domain.application.service.prompt.InterviewPrepPromptBuilder;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.global.exception.CustomException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 면접 예상 질문 생성·조회 검증.
 *
 * <p>지키려는 것은 셋이다.
 * <ul>
 *   <li><b>LLM 을 필요할 때만 부르는가</b> — 조회는 절대 부르지 않고, 생성도 이미 있으면 부르지 않는다.
 *       장학금 단위 캐시가 의미를 가지려면 이 성질이 유지돼야 한다.</li>
 *   <li><b>null 과 NOT_REQUIRED 를 구분하는가</b> — null("모른다")을 막으면 아직 파싱되지 않은
 *       장학금에서 면접 준비가 통째로 불가능해진다.</li>
 *   <li><b>지원서 없이도 되는가</b> — 자소서는 필요 없고 면접만 보는 장학금이 있다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewPrepServiceTest {

	private static final String LLM_RESPONSE = """
			1. 이 장학금에 지원한 이유는 무엇인가요? | 장학금 취지 이해도를 봅니다.
			2. 학업 중 가장 어려웠던 순간과 대응을 말씀해주세요. | 문제 해결 태도를 봅니다.
			3. 수혜 후 계획은 무엇인가요? | 지속성과 기여 의지를 봅니다.
			""";

	@Mock private ScholarshipRepository scholarshipRepository;
	@Mock private ScholarshipConditionRepository scholarshipConditionRepository;
	@Mock private InterviewPrepQuestionRepository interviewPrepQuestionRepository;
	@Mock private LlmClient llmClient;

	private InterviewPrepService service;

	@BeforeEach
	void setUp() {
		service = new InterviewPrepService(scholarshipRepository, scholarshipConditionRepository,
				interviewPrepQuestionRepository, new InterviewPrepPromptBuilder(), llmClient);
		given(scholarshipConditionRepository.findAllByScholarshipId(anyLong())).willReturn(List.of());
		given(interviewPrepQuestionRepository.findByScholarship_IdOrderByDisplayOrderAsc(anyLong()))
				.willReturn(List.of());
		given(interviewPrepQuestionRepository.saveAll(any())).willAnswer(i -> i.getArgument(0));
	}

	// --- 생성 ---

	@Test
	@DisplayName("면접이 있는 장학금은 질문을 생성해 저장한다")
	void generatesQuestions() {
		givenScholarship(RequirementLevel.CONDITIONAL);
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);

		InterviewPrepResponse response = service.generate(1L);

		assertThat(response.totalCount()).isEqualTo(3);
		assertThat(response.questions().get(0).questionText())
				.isEqualTo("이 장학금에 지원한 이유는 무엇인가요?");
		assertThat(response.questions().get(0).intent()).isEqualTo("장학금 취지 이해도를 봅니다.");
		assertThat(response.questions().get(0).displayOrder()).isZero();
		assertThat(response.questions().get(2).displayOrder()).isEqualTo(2);
	}

	@Test
	@DisplayName("이미 질문이 있으면 LLM 을 부르지 않는다 — 여러 번 호출해도 안전해야 한다")
	void doesNotRegenerate() {
		givenScholarship(RequirementLevel.REQUIRED);
		given(interviewPrepQuestionRepository.findByScholarship_IdOrderByDisplayOrderAsc(1L))
				.willReturn(List.of(question(0, "기존 질문")));

		InterviewPrepResponse response = service.generate(1L);

		verify(llmClient, never()).chat(any());
		verify(interviewPrepQuestionRepository, never()).saveAll(any());
		assertThat(response.questions()).hasSize(1);
	}

	@Test
	@DisplayName("면접을 보지 않는 장학금은 생성을 거부한다 — LLM 도 부르지 않는다")
	void rejectsWhenInterviewNotRequired() {
		givenScholarship(RequirementLevel.NOT_REQUIRED);

		assertThatThrownBy(() -> service.generate(1L)).isInstanceOf(CustomException.class);

		verify(llmClient, never()).chat(any());
	}

	@Test
	@DisplayName("면접 여부가 null(공고 언급 없음)이면 막지 않는다 — 모르는 것을 없는 것으로 취급하지 않는다")
	void allowsWhenRequirementUnknown() {
		givenScholarship(null);
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);

		InterviewPrepResponse response = service.generate(1L);

		assertThat(response.totalCount()).isEqualTo(3);
		assertThat(response.interviewRequirement()).isNull();
	}

	@Test
	@DisplayName("LLM 이 질문을 하나도 만들지 못하면 저장하지 않고 실패로 알린다")
	void failsWhenNothingGenerated() {
		givenScholarship(RequirementLevel.REQUIRED);
		given(llmClient.chat(any())).willReturn("   ");

		assertThatThrownBy(() -> service.generate(1L)).isInstanceOf(CustomException.class);

		verify(interviewPrepQuestionRepository, never()).saveAll(any());
	}

	@Test
	@DisplayName("의도(|) 가 없어도 질문만으로 저장한다 — 질문만으로도 쓸모가 있다")
	void keepsQuestionsWithoutIntent() {
		givenScholarship(RequirementLevel.REQUIRED);
		given(llmClient.chat(any())).willReturn("1. 지원 동기는 무엇인가요?\n2. 강점은 무엇인가요?");

		InterviewPrepResponse response = service.generate(1L);

		assertThat(response.totalCount()).isEqualTo(2);
		assertThat(response.questions().get(0).intent()).isNull();
	}

	@Test
	@DisplayName("면접 예상 질문은 Haiku 로 만든다 — 장학금 수만큼 비용이 쌓인다")
	void usesInterviewModel() {
		givenScholarship(RequirementLevel.REQUIRED);
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);

		service.generate(1L);

		ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
		verify(llmClient).chat(captor.capture());
		assertThat(captor.getValue().model()).isEqualTo(LlmModel.INTERVIEW);
	}

	@Test
	@DisplayName("장학금 단위로 만든다 — 지원서 없이도 생성된다")
	void worksWithoutApplication() {
		// 자소서는 필요 없고 면접만 보는 장학금. 지원서에 매달았다면 이 조합에서 질문을 줄 수 없다.
		Scholarship scholarship = givenScholarship(RequirementLevel.REQUIRED);
		setField(scholarship, "essayRequirement", RequirementLevel.NOT_REQUIRED);
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);

		assertThat(service.generate(1L).totalCount()).isEqualTo(3);
	}

	// --- 조회 ---

	@Test
	@DisplayName("조회는 LLM 을 절대 부르지 않는다 — 화면을 여는 것만으로 크레딧이 나가면 안 된다")
	void getNeverCallsLlm() {
		givenScholarship(RequirementLevel.REQUIRED);

		InterviewPrepResponse response = service.get(1L);

		verify(llmClient, never()).chat(any());
		assertThat(response.questions()).isEmpty();
		assertThat(response.totalCount()).isZero();
	}

	@Test
	@DisplayName("조회는 면접을 보지 않는 장학금에도 응답한다 — 화면이 '면접 없음'을 그려야 한다")
	void getReturnsRequirementEvenWhenNotRequired() {
		givenScholarship(RequirementLevel.NOT_REQUIRED);

		InterviewPrepResponse response = service.get(1L);

		assertThat(response.interviewRequirement()).isEqualTo(RequirementLevel.NOT_REQUIRED);
		assertThat(response.questions()).isEmpty();
	}

	@Test
	@DisplayName("판단 근거가 된 공고 문장을 함께 내려준다 — 우리 판단이 틀렸을 때 확인 수단이 된다")
	void exposesEvidence() {
		Scholarship scholarship = givenScholarship(RequirementLevel.CONDITIONAL);
		setField(scholarship, "interviewEvidence", "2차 면접전형은 서류 합격자에 한해 진행합니다.");

		assertThat(service.get(1L).interviewEvidence())
				.isEqualTo("2차 면접전형은 서류 합격자에 한해 진행합니다.");
	}

	@Test
	@DisplayName("삭제된 장학금은 찾지 않는다")
	void rejectsDeletedScholarship() {
		Scholarship scholarship = scholarship(RequirementLevel.REQUIRED);
		setField(scholarship, "deletedAt", java.time.LocalDateTime.now());
		given(scholarshipRepository.findById(1L)).willReturn(Optional.of(scholarship));

		assertThatThrownBy(() -> service.get(1L)).isInstanceOf(CustomException.class);
	}

	// --- fixture ---

	private Scholarship givenScholarship(RequirementLevel interviewRequirement) {
		Scholarship scholarship = scholarship(interviewRequirement);
		given(scholarshipRepository.findById(1L)).willReturn(Optional.of(scholarship));
		return scholarship;
	}

	private Scholarship scholarship(RequirementLevel interviewRequirement) {
		Scholarship scholarship = Scholarship.builder()
				.title("가계곤란 장학금")
				.provider("경희대학교")
				.summary("가계 형편이 어려운 재학생을 지원합니다.")
				.scholarshipType(ScholarshipType.INTERNAL)
				.primarySource("UNIV_KHU")
				.dedupKey("key-1")
				.build();
		setField(scholarship, "id", 1L);
		setField(scholarship, "interviewRequirement", interviewRequirement);
		return scholarship;
	}

	private InterviewPrepQuestion question(int order, String text) {
		return InterviewPrepQuestion.builder()
				.displayOrder(order)
				.questionText(text)
				.build();
	}

	private static void setField(Object target, String name, Object value) {
		try {
			Class<?> current = target.getClass();
			while (current != null && current != Object.class) {
				try {
					Field field = current.getDeclaredField(name);
					field.setAccessible(true);
					field.set(target, value);
					return;
				} catch (NoSuchFieldException ignored) {
					current = current.getSuperclass();
				}
			}
			throw new NoSuchFieldException(name);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
