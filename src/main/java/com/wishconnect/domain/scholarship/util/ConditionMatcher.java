package com.wishconnect.domain.scholarship.util;

import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.user.entity.UserProfile;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
장학금 조건(scholarship_condition) 1건을 사용자 프로필과 대조하는 룰 기반 평가기입니다.
공공데이터 조건은 원문(valueString) 위주라, 정형 패턴만 판정하고 나머지는 UNKNOWN(중립)으로 둡니다.
UNKNOWN을 탈락 사유로 쓰지 않는 것이 원칙입니다(파싱 안 된 조건이 많아 배제 시 추천이 비어버림).
 */
public final class ConditionMatcher {

	public enum Result { MATCH, MISMATCH, UNKNOWN }

	public record Evaluation(Result result, String description) {

		static Evaluation match(String description) {
			return new Evaluation(Result.MATCH, description);
		}

		static Evaluation mismatch(String description) {
			return new Evaluation(Result.MISMATCH, description);
		}

		static Evaluation unknown() {
			return new Evaluation(Result.UNKNOWN, null);
		}
	}

	// 예: "8분위 이하", "소득 4분위"
	private static final Pattern INCOME_LEVEL_PATTERN = Pattern.compile("([0-9]{1,2})\\s*분위");
	// 예: "2.75", "3.0 이상" (평점은 100배 정수 규약)
	private static final Pattern GPA_PATTERN = Pattern.compile("([0-4])\\.([0-9]{1,2})");
	// 예: "3학년", "2학년 이상"
	private static final Pattern GRADE_YEAR_PATTERN = Pattern.compile("([1-6])\\s*학년");
	// 예: "대학5학기", "3학년 1학기"
	private static final Pattern SEMESTER_PATTERN = Pattern.compile("([1-9]|1[0-2])\\s*학기");

	private ConditionMatcher() {
	}

	public static Evaluation evaluate(ScholarshipCondition condition, UserProfile profile) {
		if (condition == null || profile == null) {
			return Evaluation.unknown();
		}
		try {
			return switch (condition.getConditionType()) {
				case INCOME_CRITERIA -> evaluateIncome(condition, profile);
				case ACADEMIC_CRITERIA -> evaluateGpa(condition, profile);
				case GRADE_LEVEL -> evaluateGrade(condition, profile);
				case REGION_RESIDENCY -> evaluateRegion(condition, profile);
				// 프로필에 대응 필드가 없거나 원문 판정이 어려운 유형은 중립 처리
				default -> Evaluation.unknown();
			};
		} catch (RuntimeException e) {
			return Evaluation.unknown();
		}
	}

	/** 소득분위: 프로필 분위 <= 기준 분위면 충족. */
	private static Evaluation evaluateIncome(ScholarshipCondition condition, UserProfile profile) {
		Integer incomeLevel = profile.getIncomeLevel();
		if (incomeLevel == null) {
			return Evaluation.unknown();
		}
		Integer threshold = condition.getValueInt() != null
			? condition.getValueInt()
			: firstInt(INCOME_LEVEL_PATTERN, condition.getValueString());
		if (threshold == null || threshold < 1 || threshold > 10) {
			return Evaluation.unknown();
		}
		return incomeLevel <= threshold
			? Evaluation.match("소득분위 충족(" + incomeLevel + "분위 ≤ " + threshold + "분위)")
			: Evaluation.mismatch("소득분위 초과(" + incomeLevel + "분위 > " + threshold + "분위)");
	}

	/** 성적: 평점 100배 정수 규약(2.75 -> 275). 누적 평점 우선, 없으면 직전학기. */
	private static Evaluation evaluateGpa(ScholarshipCondition condition, UserProfile profile) {
		BigDecimal gpa = profile.getCumulativeGpa() != null ? profile.getCumulativeGpa() : profile.getSemesterGpa();
		if (gpa == null) {
			return Evaluation.unknown();
		}
		Integer threshold = condition.getValueInt() != null
			? condition.getValueInt()
			: parseGpaTimes100(condition.getValueString());
		if (threshold == null || threshold < 100 || threshold > 450) {
			return Evaluation.unknown();
		}
		int userGpaTimes100 = gpa.movePointRight(2).intValue();
		return userGpaTimes100 >= threshold
			? Evaluation.match("성적 기준 충족(평점 " + gpa + ")")
			: Evaluation.mismatch("성적 기준 미달(평점 " + gpa + " < " + toGpaString(threshold) + ")");
	}

	/**
	 * 학년/학기: 매퍼의 BETWEEN 값은 학기 단위(대학2학기~8학기 -> 2~8).
	 * 프로필 grade(학년)를 학기 범위 {2N-1, 2N}으로 환산해 겹침 여부로 판정한다.
	 * 원문이 "N학년" 표기면 학년끼리 직접 비교한다.
	 */
	private static Evaluation evaluateGrade(ScholarshipCondition condition, UserProfile profile) {
		String profileGrade = profile.getGrade();
		Integer grade = firstInt(GRADE_YEAR_PATTERN, profileGrade);
		if (grade == null) {
			return Evaluation.unknown();
		}
		String raw = condition.getValueString() == null ? "" : condition.getValueString();
		if (condition.getValueInt() != null && condition.getValueIntMax() != null) {
			Integer semester = firstInt(SEMESTER_PATTERN, profileGrade);
			int semesterLow = semester != null ? semester : grade * 2 - 1;
			int semesterHigh = semester != null ? semester : grade * 2;
			boolean overlaps = semesterHigh >= condition.getValueInt() && semesterLow <= condition.getValueIntMax();
			return overlaps
				? Evaluation.match("학기 범위 충족(" + grade + "학년)")
				: Evaluation.mismatch("학기 범위 밖(" + grade + "학년)");
		}
		Integer yearInText = firstInt(GRADE_YEAR_PATTERN, raw);
		if (yearInText != null) {
			return grade.equals(yearInText)
				? Evaluation.match("학년 일치(" + grade + "학년)")
				: Evaluation.mismatch("학년 불일치(" + grade + "학년 ≠ " + yearInText + "학년)");
		}
		return Evaluation.unknown();
	}

	/**
	 * 지역 거주: 프로필 지역명(또는 상위 지역명)이 조건 원문에 포함되면 충족.
	 * 원문 표현이 다양해(예: "경기도 거주자", "도내 거주") 불일치 단정은 하지 않고 UNKNOWN으로 둔다.
	 */
	private static Evaluation evaluateRegion(ScholarshipCondition condition, UserProfile profile) {
		Region region = profile.getRegion();
		String raw = condition.getValueString();
		if (region == null || raw == null || raw.isBlank()) {
			return Evaluation.unknown();
		}
		if (containsRegionName(raw, region.getName())
				|| (region.getParent() != null && containsRegionName(raw, region.getParent().getName()))) {
			return Evaluation.match("거주지역 일치(" + region.getName() + ")");
		}
		return Evaluation.unknown();
	}

	private static boolean containsRegionName(String text, String regionName) {
		return regionName != null && !regionName.isBlank() && text.contains(regionName);
	}

	private static Integer firstInt(Pattern pattern, String text) {
		if (text == null) {
			return null;
		}
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
	}

	private static Integer parseGpaTimes100(String text) {
		if (text == null) {
			return null;
		}
		Matcher matcher = GPA_PATTERN.matcher(text);
		if (!matcher.find()) {
			return null;
		}
		int integerPart = Integer.parseInt(matcher.group(1));
		String fraction = matcher.group(2);
		int fractionPart = fraction.length() == 1 ? Integer.parseInt(fraction) * 10 : Integer.parseInt(fraction);
		return integerPart * 100 + fractionPart;
	}

	private static String toGpaString(int gpaTimes100) {
		return (gpaTimes100 / 100) + "." + String.format("%02d", gpaTimes100 % 100);
	}
}
