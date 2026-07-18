package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.util.ConditionMatcher.Result;
import com.wishconnect.domain.user.entity.UserProfile;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConditionMatcherTest {

	private ScholarshipCondition condition(ConditionType type, Integer valueInt, Integer valueIntMax, String raw) {
		return ScholarshipCondition.builder()
				.conditionType(type)
				.operator(ConditionOperator.EQ)
				.valueInt(valueInt)
				.valueIntMax(valueIntMax)
				.valueString(raw)
				.autoExtracted(false)
				.build();
	}

	@Nested
	@DisplayName("소득분위(INCOME_CRITERIA)")
	class Income {

		@Test
		@DisplayName("프로필 분위가 기준 이하면 MATCH")
		void match() {
			UserProfile profile = UserProfile.builder().incomeLevel(3).build();
			var evaluation = ConditionMatcher.evaluate(
					condition(ConditionType.INCOME_CRITERIA, null, null, "소득 8분위 이하"), profile);
			assertThat(evaluation.result()).isEqualTo(Result.MATCH);
		}

		@Test
		@DisplayName("프로필 분위가 기준 초과면 MISMATCH")
		void mismatch() {
			UserProfile profile = UserProfile.builder().incomeLevel(9).build();
			var evaluation = ConditionMatcher.evaluate(
					condition(ConditionType.INCOME_CRITERIA, 8, null, "소득 8분위 이하"), profile);
			assertThat(evaluation.result()).isEqualTo(Result.MISMATCH);
		}

		@Test
		@DisplayName("프로필에 분위 정보가 없으면 UNKNOWN")
		void unknownWhenProfileMissing() {
			UserProfile profile = UserProfile.builder().build();
			var evaluation = ConditionMatcher.evaluate(
					condition(ConditionType.INCOME_CRITERIA, 8, null, null), profile);
			assertThat(evaluation.result()).isEqualTo(Result.UNKNOWN);
		}

		@Test
		@DisplayName("원문에서 분위를 못 뽑으면 UNKNOWN")
		void unknownWhenUnparseable() {
			UserProfile profile = UserProfile.builder().incomeLevel(3).build();
			var evaluation = ConditionMatcher.evaluate(
					condition(ConditionType.INCOME_CRITERIA, null, null, "기초생활수급자 및 차상위계층"), profile);
			assertThat(evaluation.result()).isEqualTo(Result.UNKNOWN);
		}
	}

	@Nested
	@DisplayName("성적(ACADEMIC_CRITERIA)")
	class Gpa {

		@Test
		@DisplayName("평점(x100)이 기준 이상이면 MATCH")
		void match() {
			UserProfile profile = UserProfile.builder().cumulativeGpa(new BigDecimal("3.50")).build();
			var evaluation = ConditionMatcher.evaluate(
					condition(ConditionType.ACADEMIC_CRITERIA, 275, null, "평점 2.75 이상"), profile);
			assertThat(evaluation.result()).isEqualTo(Result.MATCH);
		}

		@Test
		@DisplayName("기준 미달이면 MISMATCH")
		void mismatch() {
			UserProfile profile = UserProfile.builder().cumulativeGpa(new BigDecimal("2.00")).build();
			var evaluation = ConditionMatcher.evaluate(
					condition(ConditionType.ACADEMIC_CRITERIA, 275, null, "평점 2.75 이상"), profile);
			assertThat(evaluation.result()).isEqualTo(Result.MISMATCH);
		}

		@Test
		@DisplayName("valueInt 없으면 원문에서 평점을 파싱한다 (3.0 -> 300)")
		void parseFromRawText() {
			UserProfile profile = UserProfile.builder().semesterGpa(new BigDecimal("3.10")).build();
			var evaluation = ConditionMatcher.evaluate(
					condition(ConditionType.ACADEMIC_CRITERIA, null, null, "직전학기 성적 3.0 이상"), profile);
			assertThat(evaluation.result()).isEqualTo(Result.MATCH);
		}
	}

	@Nested
	@DisplayName("학년/학기(GRADE_LEVEL)")
	class Grade {

		@Test
		@DisplayName("학기 BETWEEN 범위와 겹치면 MATCH (2학년 -> 3~4학기)")
		void semesterRangeMatch() {
			UserProfile profile = UserProfile.builder().grade(2).build();
			var evaluation = ConditionMatcher.evaluate(
					condition(ConditionType.GRADE_LEVEL, 2, 8, "대학2학기부터 대학8학기까지"), profile);
			assertThat(evaluation.result()).isEqualTo(Result.MATCH);
		}

		@Test
		@DisplayName("학기 범위 밖이면 MISMATCH (1학년 -> 1~2학기, 조건 5~8학기)")
		void semesterRangeMismatch() {
			UserProfile profile = UserProfile.builder().grade(1).build();
			var evaluation = ConditionMatcher.evaluate(
					condition(ConditionType.GRADE_LEVEL, 5, 8, "대학5학기부터 대학8학기까지"), profile);
			assertThat(evaluation.result()).isEqualTo(Result.MISMATCH);
		}

		@Test
		@DisplayName("원문 N학년 표기는 학년끼리 비교")
		void yearTextComparison() {
			UserProfile profile = UserProfile.builder().grade(3).build();
			var evaluation = ConditionMatcher.evaluate(
					condition(ConditionType.GRADE_LEVEL, null, null, "3학년 재학생"), profile);
			assertThat(evaluation.result()).isEqualTo(Result.MATCH);
		}
	}

	@Nested
	@DisplayName("지역(REGION_RESIDENCY)")
	class RegionResidency {

		@Test
		@DisplayName("프로필 지역명이 원문에 포함되면 MATCH")
		void match() {
			UserProfile profile = UserProfile.builder()
					.region(Region.builder().name("경기").build())
					.build();
			var evaluation = ConditionMatcher.evaluate(
					condition(ConditionType.REGION_RESIDENCY, null, null, "경기도 내 거주자"), profile);
			assertThat(evaluation.result()).isEqualTo(Result.MATCH);
		}

		@Test
		@DisplayName("포함되지 않으면 배제하지 않고 UNKNOWN")
		void unknownWhenNotContained() {
			UserProfile profile = UserProfile.builder()
					.region(Region.builder().name("부산").build())
					.build();
			var evaluation = ConditionMatcher.evaluate(
					condition(ConditionType.REGION_RESIDENCY, null, null, "경기도 내 거주자"), profile);
			assertThat(evaluation.result()).isEqualTo(Result.UNKNOWN);
		}
	}

	@Test
	@DisplayName("프로필 필드가 없는 조건 유형은 UNKNOWN")
	void unsupportedTypesAreUnknown() {
		UserProfile profile = UserProfile.builder().incomeLevel(3).build();
		var evaluation = ConditionMatcher.evaluate(
				condition(ConditionType.SPECIFIC_QUALIFICATION, null, null, "관련 자격증 소지자"), profile);
		assertThat(evaluation.result()).isEqualTo(Result.UNKNOWN);
	}

	@Test
	@DisplayName("프로필이 null 이면 UNKNOWN")
	void nullProfile() {
		var evaluation = ConditionMatcher.evaluate(
				condition(ConditionType.INCOME_CRITERIA, 8, null, null), null);
		assertThat(evaluation.result()).isEqualTo(Result.UNKNOWN);
	}
}
