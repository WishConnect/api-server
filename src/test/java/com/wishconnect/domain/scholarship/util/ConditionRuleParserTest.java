package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConditionRuleParserTest {

	@Test
	@DisplayName("소득: 'N분위 이하/이내/구간' 패턴을 LTE로 추출한다")
	void parsesIncome() {
		assertThat(ConditionRuleParser.parse(ConditionType.INCOME_CRITERIA, "소득 8분위 이하"))
				.hasValue(new ConditionRuleParser.Extracted(ConditionOperator.LTE, 8, null));
		assertThat(ConditionRuleParser.parse(ConditionType.INCOME_CRITERIA, "학자금지원 5구간 이내인 자"))
				.hasValue(new ConditionRuleParser.Extracted(ConditionOperator.LTE, 5, null));
		assertThat(ConditionRuleParser.parse(ConditionType.INCOME_CRITERIA, "기초생활수급자 및 차상위계층"))
				.isEmpty();
	}

	@Test
	@DisplayName("성적: 소수점 평점 'N.NN 이상'을 100배 정수 GTE로 추출한다")
	void parsesGpa() {
		assertThat(ConditionRuleParser.parse(ConditionType.ACADEMIC_CRITERIA, "직전학기 평점 2.75 이상"))
				.hasValue(new ConditionRuleParser.Extracted(ConditionOperator.GTE, 275, null));
		assertThat(ConditionRuleParser.parse(ConditionType.ACADEMIC_CRITERIA, "평점 3.0 이상인 자"))
				.hasValue(new ConditionRuleParser.Extracted(ConditionOperator.GTE, 300, null));
		assertThat(ConditionRuleParser.parse(ConditionType.ACADEMIC_CRITERIA, "성적 우수자"))
				.isEmpty();
	}

	@Test
	@DisplayName("학기: 범위/하한/학년 환산을 추출한다")
	void parsesSemester() {
		assertThat(ConditionRuleParser.parse(ConditionType.GRADE_LEVEL, "대학2학기부터 대학8학기까지"))
				.hasValue(new ConditionRuleParser.Extracted(ConditionOperator.BETWEEN, 2, 8));
		assertThat(ConditionRuleParser.parse(ConditionType.GRADE_LEVEL, "3학기 이상 등록자"))
				.hasValue(new ConditionRuleParser.Extracted(ConditionOperator.GTE, 3, null));
		assertThat(ConditionRuleParser.parse(ConditionType.GRADE_LEVEL, "2~4학년"))
				.hasValue(new ConditionRuleParser.Extracted(ConditionOperator.BETWEEN, 3, 8));
		assertThat(ConditionRuleParser.parse(ConditionType.GRADE_LEVEL, "3학년 재학생"))
				.hasValue(new ConditionRuleParser.Extracted(ConditionOperator.BETWEEN, 5, 6));
		assertThat(ConditionRuleParser.parse(ConditionType.GRADE_LEVEL, "학년 무관"))
				.isEmpty();
	}

	@Test
	@DisplayName("지원 유형 외/빈 문자열은 추출하지 않는다")
	void skipsUnsupported() {
		assertThat(ConditionRuleParser.parse(ConditionType.RESTRICTION, "소득 8분위 이하")).isEmpty();
		assertThat(ConditionRuleParser.parse(ConditionType.INCOME_CRITERIA, "  ")).isEmpty();
		assertThat(ConditionRuleParser.parse(ConditionType.INCOME_CRITERIA, null)).isEmpty();
	}
}
