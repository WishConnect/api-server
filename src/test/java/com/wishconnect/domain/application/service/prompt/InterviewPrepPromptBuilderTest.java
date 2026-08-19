package com.wishconnect.domain.application.service.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 면접 예상 질문 프롬프트 조립·응답 검증.
 *
 * <p>여기서 막으려는 것은 <b>LLM 이 형식을 어겼을 때 질문이 아닌 문장이 저장되는 것</b>이다.
 * 번호 목록 파싱이 실패하면 모든 줄을 후보로 보기 때문에, 머리말·코드펜스·맺음말이 그대로
 * 질문이 되어 화면에 뜬다.
 */
class InterviewPrepPromptBuilderTest {

	private final InterviewPrepPromptBuilder builder = new InterviewPrepPromptBuilder();

	@Test
	@DisplayName("번호 목록에서 질문과 의도를 뽑는다")
	void parsesNumberedList() {
		List<InterviewPrepPromptBuilder.Generated> result = builder.parse("""
				1. 이 장학금에 지원한 이유는 무엇인가요? | 취지 이해도를 봅니다.
				2. 학업 중 어려웠던 순간을 말씀해주세요. | 문제 해결 태도를 봅니다.
				""");

		assertThat(result).hasSize(2);
		assertThat(result.get(0).questionText()).isEqualTo("이 장학금에 지원한 이유는 무엇인가요?");
		assertThat(result.get(0).intent()).isEqualTo("취지 이해도를 봅니다.");
	}

	@Test
	@DisplayName("의도가 없어도 질문만으로 남긴다 — 질문만으로도 쓸모가 있다")
	void keepsQuestionWithoutIntent() {
		List<InterviewPrepPromptBuilder.Generated> result =
				builder.parse("1. 본인의 강점은 무엇인가요?");

		assertThat(result).hasSize(1);
		assertThat(result.get(0).intent()).isNull();
	}

	@Test
	@DisplayName("머리말·맺음말은 질문으로 저장하지 않는다")
	void dropsPreambleAndClosing() {
		List<InterviewPrepPromptBuilder.Generated> result = builder.parse("""
				아래와 같이 면접 예상 질문을 만들었습니다.
				1. 지원 동기는 무엇인가요?
				이상입니다. 준비에 도움이 되길 바랍니다.
				""");

		assertThat(result).hasSize(1);
		assertThat(result.get(0).questionText()).isEqualTo("지원 동기는 무엇인가요?");
	}

	@Test
	@DisplayName("번호가 없는 응답에서도 질문 형태만 남긴다")
	void filtersNonQuestionsInFallback() {
		List<InterviewPrepPromptBuilder.Generated> result = builder.parse("""
				```json
				## 면접 예상 질문
				이 장학금에 지원한 이유는 무엇인가요?
				**참고**: 학교마다 다를 수 있습니다
				수혜 후 계획을 말씀해주세요.
				```
				""");

		assertThat(result).hasSize(2);
		assertThat(result).noneMatch(g -> g.questionText().contains("참고"));
		assertThat(result).noneMatch(g -> g.questionText().startsWith("#"));
	}

	@Test
	@DisplayName("너무 길거나 짧은 줄은 버린다")
	void dropsOutOfRangeLength() {
		String tooLong = "1. " + "가".repeat(80) + "인가요?";
		List<InterviewPrepPromptBuilder.Generated> result = builder.parse(tooLong + "\n2. 네?");

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("같은 질문이 두 번 오면 하나만 남긴다")
	void removesDuplicates() {
		List<InterviewPrepPromptBuilder.Generated> result = builder.parse("""
				1. 지원 동기는 무엇인가요?
				2. 지원 동기는 무엇인가요?
				3. 강점은 무엇인가요?
				""");

		assertThat(result).hasSize(2);
	}

	@Test
	@DisplayName("요청 개수를 넘겨 생성하면 앞에서부터 자른다")
	void trimsToRequestedCount() {
		StringBuilder response = new StringBuilder();
		for (int i = 1; i <= 10; i++) {
			response.append(i).append(". 질문 ").append(i).append(" 무엇인가요?\n");
		}

		assertThat(builder.parse(response.toString()))
				.hasSize(InterviewPrepPromptBuilder.QUESTION_COUNT);
	}

	@Test
	@DisplayName("응답이 비었거나 질문이 하나도 없으면 빈 리스트")
	void returnsEmptyWhenNothingUsable() {
		assertThat(builder.parse(null)).isEmpty();
		assertThat(builder.parse("   ")).isEmpty();
		assertThat(builder.parse("죄송합니다. 정보가 부족해 만들 수 없습니다.")).isEmpty();
	}

	@Test
	@DisplayName("자격조건이 없으면 '확인되지 않음' 이라고 적어 보낸다 — 비우면 모델이 지어낸다")
	void statesMissingConditionsExplicitly() {
		LlmChatRequest request = builder.build(scholarship(), List.of());

		assertThat(request.systemPrompt()).contains("(공고에서 확인되지 않음)");
	}

	@Test
	@DisplayName("면접 근거 문장을 프롬프트에 싣는다")
	void includesInterviewEvidence() {
		Scholarship scholarship = scholarship();
		setField(scholarship, "interviewEvidence", "2차 면접은 서류 합격자에 한해 진행합니다.");

		assertThat(builder.build(scholarship, List.of()).systemPrompt())
				.contains("2차 면접은 서류 합격자에 한해 진행합니다.");
	}

	private Scholarship scholarship() {
		Scholarship scholarship = Scholarship.builder()
				.title("가계곤란 장학금")
				.provider("경희대학교")
				.scholarshipType(ScholarshipType.INTERNAL)
				.primarySource("UNIV_KHU")
				.dedupKey("key-1")
				.build();
		setField(scholarship, "interviewRequirement", RequirementLevel.CONDITIONAL);
		return scholarship;
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
