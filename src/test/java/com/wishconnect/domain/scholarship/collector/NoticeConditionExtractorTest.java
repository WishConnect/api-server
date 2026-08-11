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
	@DisplayName("모집 학기와 제한 문장을 학년 조건으로 오분류하지 않는다")
	void doesNotExtractRecruitmentSemesterOrRestrictionAsGradeLevel() {
		assertThat(typesOf("신청대상 - 2026-2학기 재학생")).doesNotContain(ConditionType.GRADE_LEVEL);
		assertThat(typesOf("① 반드시, 합격한 당해 학기에 신청하여야 하며, 2026-2학기 휴학생 및 초과학기생은 신청 불가"))
				.contains(ConditionType.RESTRICTION)
				.doesNotContain(ConditionType.GRADE_LEVEL);
	}

	@Test
	@DisplayName("취득학점 요건은 평점 조건으로 오분류하지 않는다")
	void doesNotExtractCreditHourAsAcademicCriteria() {
		assertThat(typesOf("직전학기 취득학점 15학점 이상")).doesNotContain(ConditionType.ACADEMIC_CRITERIA);
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
	@DisplayName("같은 원문 조건이 반복되면 중복 저장하지 않는다")
	void deduplicatesSameCondition() {
		var result = NoticeConditionExtractor.extract("""
				소득 8분위 이하
				소득 8분위 이하
				평점 3.0 이상
				""");
		assertThat(result).hasSize(2);
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

	@Test
	@DisplayName("동국대 번호형 지원자격에서 소득·재학·특수자격·제한 조건을 추출한다")
	void extractsDonggukNumberedQualificationSection() {
		var result = NoticeConditionExtractor.extract("""
				1. 장학명: 진담거사 지역미래불자육성장학
				3. 장학금: 100만원(생활비성)
				   ※타 불교계장학 이중수혜 불가
				6. 지원 자격
				   ① 급격한 가정 환경 변화 혹은 과도한 아르바이트로 학업 지속이 어려운 학생
				   ② 2026학년도 2학기 재학생으로서 2026학년도 2학기 기준 소득분위 0~3분위인 학생
				      또는 2026학년도 2학기 재학생으로서 '한부모 가정'의 학생, 가족을 간병하고 있는 학생
				   ③ 불교동아리 부원이거나 가입 예정인 학생
				8. 신청기한: ~7.21.(화) 23:59까지
				""");

		assertThat(result).extracting(NoticeConditionExtractor.Extracted::type)
				.contains(
						ConditionType.RESTRICTION,
						ConditionType.INCOME_CRITERIA,
						ConditionType.SPECIFIC_QUALIFICATION
				);
		assertThat(result).anySatisfy(e -> {
			assertThat(e.type()).isEqualTo(ConditionType.INCOME_CRITERIA);
			assertThat(e.snippet()).contains("0~3분위");
		});
		assertThat(result).anySatisfy(e -> {
			assertThat(e.type()).isEqualTo(ConditionType.RESTRICTION);
			assertThat(e.snippet()).contains("이중수혜 불가");
		});
	}
}
