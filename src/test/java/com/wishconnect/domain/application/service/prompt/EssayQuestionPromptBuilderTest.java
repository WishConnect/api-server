package com.wishconnect.domain.application.service.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 장학금 맞춤 문항 생성 검증.
 *
 * <p>여기서 지키려는 것은 하나다 — <b>공고에 없는 것을 묻는 문항이 통과하지 못하게 한다.</b>
 * 근거 없는 맞춤 문항은 학생에게 없는 경험을 지어내게 만들어, 무난한 기본 문항보다 나쁘다.
 * 그래서 살아남은 문항이 모자라면 빈 리스트를 돌려 호출자가 기본 문항을 쓰게 한다.
 */
class EssayQuestionPromptBuilderTest {

	private static final String SOURCE = """
			2026학년도 지역인재 장학금
			경희대학교
			경기도 출신 학생의 학업을 지원합니다.
			신청자격 : 경기도에 주민등록을 둔 자로서 직전학기 평점 3.5 이상인 자
			선발기준 : 학업계획서 평가 50%, 성적 50%
			""";

	private final EssayQuestionPromptBuilder builder =
			new EssayQuestionPromptBuilder(new ObjectMapper());

	@Test
	@DisplayName("근거가 공고에 있는 문항만 받아들인다")
	void acceptsGroundedQuestions() {
		List<EssayQuestionPromptBuilder.GeneratedQuestion> result = builder.parse("""
				[{"title":"지역 연고","description":"경기도와의 인연을 서술해주세요.","charLimit":600,
				  "basis":"경기도에 주민등록을 둔 자"},
				 {"title":"학업 계획","description":"앞으로의 학업 계획을 서술해주세요.","charLimit":800,
				  "basis":"학업계획서 평가 50%"}]
				""", SOURCE);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).title()).isEqualTo("지역 연고");
		assertThat(result.get(0).charLimit()).isEqualTo(600);
	}

	@Test
	@DisplayName("공고에 없는 근거를 댄 문항은 버린다 — 지어낸 질문을 막는다")
	void dropsUngroundedQuestion() {
		List<EssayQuestionPromptBuilder.GeneratedQuestion> result = builder.parse("""
				[{"title":"봉사 경험","description":"봉사활동 경험을 서술해주세요.","charLimit":600,
				  "basis":"봉사활동 실적이 우수한 자"},
				 {"title":"학업 계획","description":"앞으로의 학업 계획을 서술해주세요.","charLimit":800,
				  "basis":"학업계획서 평가 50%"},
				 {"title":"지역 연고","description":"경기도와의 인연을 서술해주세요.","charLimit":600,
				  "basis":"경기도에 주민등록을 둔 자"}]
				""", SOURCE);

		assertThat(result).hasSize(2);
		assertThat(result).noneMatch(q -> q.title().equals("봉사 경험"));
	}

	@Test
	@DisplayName("근거를 통과한 문항이 2개 미만이면 빈 리스트 — 기본 문항으로 되돌린다")
	void fallsBackWhenTooFewSurvive() {
		List<EssayQuestionPromptBuilder.GeneratedQuestion> result = builder.parse("""
				[{"title":"학업 계획","description":"앞으로의 학업 계획을 서술해주세요.","charLimit":800,
				  "basis":"학업계획서 평가 50%"},
				 {"title":"봉사 경험","description":"봉사활동 경험을 서술해주세요.","charLimit":600,
				  "basis":"봉사활동 실적이 우수한 자"}]
				""", SOURCE);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("근거가 없거나 너무 짧으면 버린다 — 짧은 인용은 우연히 일치한다")
	void rejectsMissingOrShortBasis() {
		assertThat(EssayQuestionPromptBuilder.isGrounded(null, SOURCE)).isFalse();
		assertThat(EssayQuestionPromptBuilder.isGrounded("  ", SOURCE)).isFalse();
		assertThat(EssayQuestionPromptBuilder.isGrounded("경기도", SOURCE)).isFalse();
		assertThat(EssayQuestionPromptBuilder.isGrounded("경기도에 주민등록", SOURCE)).isTrue();
	}

	@Test
	@DisplayName("공백·문장부호가 달라도 근거로 인정한다 — 표기 차이로 멀쩡한 문항을 버리지 않는다")
	void toleratesPunctuationDifference() {
		assertThat(EssayQuestionPromptBuilder.isGrounded("직전학기 평점 3.5 이상", SOURCE)).isTrue();
		assertThat(EssayQuestionPromptBuilder.isGrounded("직전학기평점3.5이상!", SOURCE)).isTrue();
	}

	@Test
	@DisplayName("제목·질문 길이가 범위를 벗어나면 버린다")
	void rejectsOutOfRangeText() {
		List<EssayQuestionPromptBuilder.GeneratedQuestion> result = builder.parse("""
				[{"title":"이 제목은 화면 탭에 절대 들어가지 않을 만큼 길게 작성된 제목입니다",
				  "description":"경기도와의 인연을 서술해주세요.","charLimit":600,
				  "basis":"경기도에 주민등록을 둔 자"},
				 {"title":"학업","description":"짧음","charLimit":800,
				  "basis":"학업계획서 평가 50%"}]
				""", SOURCE);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("글자수가 범위를 벗어나면 기본값으로 되돌린다")
	void normalizesCharLimit() {
		List<EssayQuestionPromptBuilder.GeneratedQuestion> result = builder.parse("""
				[{"title":"지역 연고","description":"경기도와의 인연을 서술해주세요.","charLimit":50,
				  "basis":"경기도에 주민등록을 둔 자"},
				 {"title":"학업 계획","description":"앞으로의 학업 계획을 서술해주세요.","charLimit":99999,
				  "basis":"학업계획서 평가 50%"}]
				""", SOURCE);

		assertThat(result).hasSize(2);
		assertThat(result).allMatch(q -> q.charLimit() == 800);
	}

	@Test
	@DisplayName("같은 제목이 두 번 오면 하나만 남긴다")
	void removesDuplicateTitles() {
		List<EssayQuestionPromptBuilder.GeneratedQuestion> result = builder.parse("""
				[{"title":"학업 계획","description":"학업 계획을 서술해주세요.","charLimit":800,
				  "basis":"학업계획서 평가 50%"},
				 {"title":"학업 계획","description":"연구 계획을 서술해주세요.","charLimit":800,
				  "basis":"학업계획서 평가 50%"},
				 {"title":"지역 연고","description":"경기도와의 인연을 서술해주세요.","charLimit":600,
				  "basis":"경기도에 주민등록을 둔 자"}]
				""", SOURCE);

		assertThat(result).hasSize(2);
	}

	@Test
	@DisplayName("빈 배열·잘못된 JSON·코드펜스를 모두 견딘다")
	void handlesMalformedResponses() {
		assertThat(builder.parse(null, SOURCE)).isEmpty();
		assertThat(builder.parse("[]", SOURCE)).isEmpty();
		assertThat(builder.parse("죄송합니다. 만들 수 없습니다.", SOURCE)).isEmpty();
		assertThat(builder.parse("""
				```json
				[{"title":"지역 연고","description":"경기도와의 인연을 서술해주세요.","charLimit":600,
				  "basis":"경기도에 주민등록을 둔 자"},
				 {"title":"학업 계획","description":"학업 계획을 서술해주세요.","charLimit":800,
				  "basis":"학업계획서 평가 50%"}]
				```
				""", SOURCE)).hasSize(2);
	}

	@Test
	@DisplayName("최대 개수를 넘기면 앞에서부터 자른다")
	void trimsToMaxQuestions() {
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < 8; i++) {
			json.append(i == 0 ? "" : ",")
					.append("{\"title\":\"문항 ").append(i)
					.append("\",\"description\":\"내용을 서술해주세요 ").append(i)
					.append("\",\"charLimit\":800,\"basis\":\"학업계획서 평가 50%\"}");
		}
		json.append("]");

		assertThat(builder.parse(json.toString(), SOURCE))
				.hasSize(EssayQuestionPromptBuilder.MAX_QUESTIONS);
	}

	@Test
	@DisplayName("근거 대조에 쓰는 텍스트는 프롬프트에 넣은 재료와 같다")
	void sourceTextMatchesPrompt() {
		Scholarship scholarship = scholarship();
		String sourceText = builder.sourceTextOf(scholarship, List.of());
		String prompt = builder.build(scholarship, List.of()).systemPrompt();

		// 모델에게 보여주지 않은 곳에서 인용했다고 통과시키면 검증이 무의미해진다.
		assertThat(sourceText).contains("지역인재 장학금");
		assertThat(prompt).contains("지역인재 장학금");
	}

	@Test
	@DisplayName("자격조건이 없으면 '확인되지 않음' 이라고 적어 보낸다 — 비우면 모델이 지어낸다")
	void statesMissingConditionsExplicitly() {
		assertThat(builder.build(scholarship(), List.of()).systemPrompt())
				.contains("(공고에서 확인되지 않음)");
	}

	private Scholarship scholarship() {
		Scholarship scholarship = Scholarship.builder()
				.title("지역인재 장학금")
				.provider("경희대학교")
				.summary("경기도 출신 학생의 학업을 지원합니다.")
				.scholarshipType(ScholarshipType.INTERNAL)
				.primarySource("UNIV_KHU")
				.dedupKey("key-1")
				.build();
		setField(scholarship, "id", 1L);
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
