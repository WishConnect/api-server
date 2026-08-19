package com.wishconnect.domain.application.service.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 면접 예상 질문 생성·검증.
 *
 * <p>기획 확정본은 질문 하나에 질문의도·답변 Tip·구성 가이드를 함께 보여준다. 여기서 지키려는
 * 것은 <b>모델이 형식을 어겼을 때 화면이 깨지지 않는 것</b>이다. 질문이 성립하지 않으면 버리고,
 * 부가 정보만 빠지면 질문은 살린다. 반쪽짜리 구성 가이드는 통째로 비운다.
 */
class InterviewPrepPromptBuilderTest {

	private final InterviewPrepPromptBuilder builder =
			new InterviewPrepPromptBuilder(new ObjectMapper());

	private static final String FULL = """
			[{"question":"본인의 가장 큰 장점은 무엇인가요?",
			  "intent":"지원자의 핵심 역량과 강점을 파악하기 위한 질문입니다.",
			  "answerTip":"구체적인 경험을 들어 설명하면 신뢰도를 높일 수 있어요.",
			  "sampleAnswer":"저의 가장 큰 강점은 지속적으로 배우고 성장하려는 태도입니다. 새로운 환경에 빠르게 적응하고 모르는 것을 먼저 물어보며 배웁니다. 그 결과 맡은 일을 끝까지 마무리할 수 있었습니다.",
			  "guide":[{"title":"강점제시","description":"핵심 강점을 한 문장으로 먼저 밝히세요."},
			           {"title":"경험 설명","description":"상황·행동·결과 순서로 뒷받침하세요."},
			           {"title":"성장 및 활용","description":"배운 점과 앞으로의 활용을 마무리하세요."}]}]
			""";

	@Test
	@DisplayName("질문·의도·Tip·구성가이드를 모두 읽는다")
	void parsesAllSections() {
		List<InterviewPrepPromptBuilder.GeneratedQuestion> result = builder.parse(FULL);

		assertThat(result).hasSize(1);
		var q = result.get(0);
		assertThat(q.questionText()).isEqualTo("본인의 가장 큰 장점은 무엇인가요?");
		assertThat(q.intent()).contains("핵심 역량");
		assertThat(q.answerTip()).contains("구체적인 경험");
		assertThat(q.guideSteps()).hasSize(3);
		assertThat(q.guideSteps().get(0).title()).isEqualTo("강점제시");
		assertThat(q.guideSteps().get(2).title()).isEqualTo("성장 및 활용");
		assertThat(q.sampleAnswer()).contains("지속적으로 배우고");
	}

	@Test
	@DisplayName("일반 예시답변도 함께 만든다 — 자소서를 받지 않는 장학금은 이것만 볼 수 있다")
	void generatesGenericSampleAnswer() {
		String prompt = builder.build(scholarship(), List.of()).systemPrompt();

		assertThat(prompt).contains("sampleAnswer");
		// 확인할 수 없는 사실을 넣으면 학생이 면접에서 답하지 못한다.
		assertThat(prompt).contains("확인할 수 없는 사실은 넣지 마세요");
	}

	@Test
	@DisplayName("예시답변이 너무 짧으면 버리되 질문은 살린다")
	void dropsTooShortSampleAnswer() {
		List<InterviewPrepPromptBuilder.GeneratedQuestion> result = builder.parse("""
				[{"question":"지원하게 된 동기는 무엇인가요?","sampleAnswer":"열심히 하겠습니다."}]
				""");

		assertThat(result).hasSize(1);
		assertThat(result.get(0).sampleAnswer()).isNull();
	}

	@Test
	@DisplayName("의도·Tip 이 없어도 질문은 살린다 — 질문만으로도 쓸모가 있다")
	void keepsQuestionWithoutOptionalFields() {
		List<InterviewPrepPromptBuilder.GeneratedQuestion> result = builder.parse("""
				[{"question":"지원하게 된 동기는 무엇인가요?"}]
				""");

		assertThat(result).hasSize(1);
		assertThat(result.get(0).intent()).isNull();
		assertThat(result.get(0).answerTip()).isNull();
		assertThat(result.get(0).guideSteps()).isEmpty();
	}

	@Test
	@DisplayName("구성 가이드가 3단계에 못 미치면 통째로 비운다 — 반쪽 흐름이 더 혼란스럽다")
	void dropsIncompleteGuide() {
		List<InterviewPrepPromptBuilder.GeneratedQuestion> result = builder.parse("""
				[{"question":"지원하게 된 동기는 무엇인가요?",
				  "guide":[{"title":"동기제시","description":"먼저 밝히세요."},
				           {"title":"경험","description":"뒷받침하세요."}]}]
				""");

		assertThat(result).hasSize(1);
		assertThat(result.get(0).guideSteps()).isEmpty();
	}

	@Test
	@DisplayName("단계 내용이 비면 가이드 전체를 비운다")
	void dropsGuideWithEmptyStep() {
		List<InterviewPrepPromptBuilder.GeneratedQuestion> result = builder.parse("""
				[{"question":"지원하게 된 동기는 무엇인가요?",
				  "guide":[{"title":"동기제시","description":"먼저 밝히세요."},
				           {"title":"","description":"뒷받침하세요."},
				           {"title":"마무리","description":"정리하세요."}]}]
				""");

		assertThat(result.get(0).guideSteps()).isEmpty();
	}

	@Test
	@DisplayName("너무 길거나 짧은 질문은 버린다")
	void dropsOutOfRangeQuestion() {
		String tooLong = "\"question\":\"" + "가".repeat(80) + "인가요?\"";
		assertThat(builder.parse("[{" + tooLong + "}]")).isEmpty();
		assertThat(builder.parse("[{\"question\":\"네?\"}]")).isEmpty();
	}

	@Test
	@DisplayName("같은 질문이 두 번 오면 하나만 남긴다")
	void removesDuplicates() {
		List<InterviewPrepPromptBuilder.GeneratedQuestion> result = builder.parse("""
				[{"question":"지원하게 된 동기는 무엇인가요?"},
				 {"question":"지원하게 된 동기는 무엇인가요?"},
				 {"question":"본인의 강점은 무엇인가요?"}]
				""");

		assertThat(result).hasSize(2);
	}

	@Test
	@DisplayName("코드펜스로 감싸 와도 읽는다")
	void parsesFencedJson() {
		assertThat(builder.parse("```json\n" + FULL + "\n```")).hasSize(1);
	}

	@Test
	@DisplayName("응답이 비었거나 JSON 이 아니면 빈 리스트")
	void returnsEmptyOnMalformed() {
		assertThat(builder.parse(null)).isEmpty();
		assertThat(builder.parse("   ")).isEmpty();
		assertThat(builder.parse("죄송합니다. 만들 수 없습니다.")).isEmpty();
	}

	@Test
	@DisplayName("요청 개수를 넘기면 앞에서부터 자른다")
	void trimsToRequestedCount() {
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < 10; i++) {
			json.append(i == 0 ? "" : ",")
					.append("{\"question\":\"질문 ").append(i).append(" 무엇인가요?\"}");
		}
		json.append("]");

		assertThat(builder.parse(json.toString()))
				.hasSize(InterviewPrepPromptBuilder.QUESTION_COUNT);
	}

	@Test
	@DisplayName("자격조건이 없으면 '확인되지 않음' 이라고 적어 보낸다 — 비우면 모델이 지어낸다")
	void statesMissingConditionsExplicitly() {
		assertThat(builder.build(scholarship(), List.of()).systemPrompt())
				.contains("(공고에서 확인되지 않음)");
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
