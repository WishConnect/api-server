package com.wishconnect.domain.scholarship.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.wishconnect.domain.scholarship.entity.ConditionType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NoticeConditionExtractorTest {

	private static List<ConditionType> typesOf(String body) {
		return NoticeConditionExtractor.extract(body).stream()
				.map(NoticeConditionExtractor.Extracted::type)
				.toList();
	}

	@Test
	@DisplayName("소득분위 문장을 INCOME_CRITERIA 로 추출한다")
	void extractsIncome() {
		var result = NoticeConditionExtractor.extract("""
				[지원자격]
				- 2026학년도 2학기 재학생
				- 소득분위 8분위 이하인 자
				""");
		assertThat(typesOf("소득분위 8분위 이하인 자")).contains(ConditionType.INCOME_CRITERIA);
		assertThat(result).anySatisfy(e -> {
			assertThat(e.type()).isEqualTo(ConditionType.INCOME_CRITERIA);
			assertThat(e.snippet()).contains("8분위");
		});
	}

	@Test
	@DisplayName("학자금 지원구간 표현도 소득 조건으로 본다")
	void extractsIncomeFromSupportSection() {
		assertThat(typesOf("학자금 지원구간 4구간 이내 학생"))
				.contains(ConditionType.INCOME_CRITERIA);
	}

	@Test
	@DisplayName("성적 기준 문장을 ACADEMIC_CRITERIA 로 추출한다")
	void extractsAcademic() {
		assertThat(typesOf("직전학기 평점평균 3.0 이상")).contains(ConditionType.ACADEMIC_CRITERIA);
		assertThat(typesOf("성적 80점 이상인 자")).contains(ConditionType.ACADEMIC_CRITERIA);
	}

	@Test
	@DisplayName("학년/학기 기준 문장을 GRADE_LEVEL 로 추출한다")
	void extractsGradeLevel() {
		assertThat(typesOf("2학년 이상 재학생")).contains(ConditionType.GRADE_LEVEL);
		assertThat(typesOf("대학 3학기~7학기 재학 중인 학생")).contains(ConditionType.GRADE_LEVEL);
	}

	@Test
	@DisplayName("거주지 요건을 REGION_RESIDENCY 로 추출한다")
	void extractsRegion() {
		assertThat(typesOf("부산광역시에 거주하는 대학생")).contains(ConditionType.REGION_RESIDENCY);
		assertThat(typesOf("전라북도 출신 학생 대상")).contains(ConditionType.REGION_RESIDENCY);
	}

	@Test
	@DisplayName("지급·발표 안내 문장은 자격 조건으로 보지 않는다")
	void ignoresPaymentAndResultSentences() {
		assertThat(typesOf("장학금 지급일은 3학년 대상 8월 20일입니다")).isEmpty();
		assertThat(typesOf("선발 결과는 평점 3.0 이상자를 대상으로 발표합니다")).isEmpty();
	}

	@Test
	@DisplayName("조건이 없으면 아무것도 만들지 않는다")
	void returnsEmptyWhenNoCondition() {
		assertThat(NoticeConditionExtractor.extract("장학생 선발 안내입니다. 자세한 내용은 첨부파일 참고."))
				.isEmpty();
		assertThat(NoticeConditionExtractor.extract(null)).isEmpty();
		assertThat(NoticeConditionExtractor.extract("  ")).isEmpty();
	}

	@Test
	@DisplayName("유형별로 최대 1건만 만든다(같은 조건 문장이 반복돼도 중복 저장하지 않음)")
	void keepsOnePerType() {
		var result = NoticeConditionExtractor.extract("""
				소득 8분위 이하
				소득 9분위 이하
				평점 3.0 이상
				""");
		assertThat(result).hasSize(2);
		assertThat(typesOf("소득 8분위 이하\n소득 9분위 이하"))
				.containsExactly(ConditionType.INCOME_CRITERIA);
	}

	@Test
	@DisplayName("추출된 원문은 ConditionRuleParser 가 숫자로 구조화할 수 있는 형태다")
	void snippetIsParsableByRuleParser() {
		var income = NoticeConditionExtractor.extract("지원자격: 소득 8분위 이하인 재학생").get(0);
		var parsed = com.wishconnect.domain.scholarship.util.ConditionRuleParser
				.parse(income.type(), income.snippet());
		assertThat(parsed).isPresent();
		assertThat(parsed.get().valueInt()).isEqualTo(8);
	}
}
