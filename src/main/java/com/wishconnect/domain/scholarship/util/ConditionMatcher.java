package com.wishconnect.domain.scholarship.util;

import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.scholarship.entity.ConditionRef;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.user.entity.UserProfile;
import java.math.BigDecimal;
import java.util.Set;
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

	/** 연결 테이블을 읽지 않은 호출용. 참조 대조가 필요한 유형은 판정 불가로 넘어간다. */
	public static Evaluation evaluate(ScholarshipCondition condition, UserProfile profile) {
		return evaluate(condition, MatchProfile.of(profile));
	}

	public static Evaluation evaluate(ScholarshipCondition condition, MatchProfile matchProfile) {
		if (condition == null || matchProfile == null || matchProfile.profile() == null) {
			return Evaluation.unknown();
		}
		UserProfile profile = matchProfile.profile();
		try {
			return switch (condition.getConditionType()) {
				case INCOME_CRITERIA -> evaluateIncome(condition, profile);
				case ACADEMIC_CRITERIA -> evaluateGpa(condition, profile);
				case GRADE_LEVEL -> evaluateGrade(condition, profile);
				case REGION_RESIDENCY -> evaluateRegion(condition, matchProfile);
				case SPECIFIC_QUALIFICATION -> evaluateQualification(condition, matchProfile);
				case MAJOR_FIELD -> evaluateMajor(condition, matchProfile);
				case FINANCIAL_AID_TYPE -> evaluateAidType(condition, matchProfile);
				case RESTRICTION -> evaluateRestriction(condition, matchProfile);
				// 프로필에 대응 필드가 없는 유형(대학구분·추천서)은 중립 처리
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
	 * 지역 거주.
	 *
	 * <p>참조가 있으면 <b>"아니다" 를 말할 수 있다.</b> 그전에는 원문 포함 여부만 봐서
	 * 안 맞으면 {@code UNKNOWN}(=통과) 이었고, {@code "서구"} 는 대구·인천·광주·대전·부산에
	 * 다 있어 엉뚱한 사람이 통과했다. ID 로 비교하면 둘 다 사라진다.
	 *
	 * <p>사용자 지역은 시군구와 상위 시도를 함께 담고 있다. 조건이 {@code "대구"} 로 걸려 있는데
	 * 사용자가 {@code "대구 서구"} 를 골랐다고 탈락시키면 안 되기 때문이다.
	 *
	 * <p>참조가 없는 조건은 예전 방식(원문 포함)으로 남긴다 — 규칙으로 해석되지 않은 서술형이라
	 * 불일치를 단정할 근거가 없다.
	 */
	private static Evaluation evaluateRegion(ScholarshipCondition condition, MatchProfile matchProfile) {
		Region region = matchProfile.profile().getRegion();
		if (region == null) {
			return Evaluation.unknown();
		}
		Set<ConditionRef> refs = condition.getRefs();
		if (refs != null && !refs.isEmpty()) {
			boolean covered = refs.stream()
					.map(ConditionRef::getRefId)
					.anyMatch(refId -> refId != null && matchProfile.regionIds().contains(refId));
			return covered
				? Evaluation.match("거주지역 일치(" + region.getName() + ")")
				: Evaluation.mismatch("거주지역 불일치(" + region.getName() + ")");
		}

		String raw = condition.getValueString();
		if (raw == null || raw.isBlank()) {
			return Evaluation.unknown();
		}
		if (containsRegionName(raw, region.getName())
				|| (region.getParent() != null && containsRegionName(raw, region.getParent().getName()))) {
			return Evaluation.match("거주지역 일치(" + region.getName() + ")");
		}
		return Evaluation.unknown();
	}

	/**
	 * 본인해당·가정형태(수급자·차상위·한부모 등).
	 *
	 * <p>참조는 <b>OR 로 묶인 요건</b>이다("기초생활수급자 또는 차상위계층"). 그래서 교집합이
	 * 비어 있지 않으면 충족이다. 사용자가 아무것도 고르지 않았으면 "없다"가 아니라 "모른다"라
	 * 판정하지 않는다 — 온보딩에서 건너뛴 것과 해당 없음을 구별할 수 없다.
	 */
	private static Evaluation evaluateQualification(ScholarshipCondition condition, MatchProfile matchProfile) {
		Set<Long> refIds = idRefsOf(condition);
		if (refIds.isEmpty() || matchProfile.familyTypeIds().isEmpty()) {
			return Evaluation.unknown();
		}
		return refIds.stream().anyMatch(matchProfile.familyTypeIds()::contains)
			? Evaluation.match("지원 자격 해당")
			: Evaluation.mismatch("지원 자격 미해당");
	}

	/** 전공 계열. 마스터가 enum 이라 코드로 비교한다. */
	private static Evaluation evaluateMajor(ScholarshipCondition condition, MatchProfile matchProfile) {
		Set<String> codes = codeRefsOf(condition);
		String userCode = matchProfile.majorCategoryCode();
		if (codes.isEmpty() || userCode == null) {
			return Evaluation.unknown();
		}
		return codes.contains(userCode)
			? Evaluation.match("전공 계열 일치")
			: Evaluation.mismatch("전공 계열 불일치");
	}

	/**
	 * 지원 성격(등록금·생활비·해외연수).
	 *
	 * <p>자격이 아니라 분류라 조건 자체가 우대({@code PREFERRED})로 저장된다. 여기서 불일치가
	 * 나와도 탈락시키지 않고 <b>순위만</b> 낮춘다 — 게이트로 쓸지 말지는 호출측이 필수 여부로 정한다.
	 */
	private static Evaluation evaluateAidType(ScholarshipCondition condition, MatchProfile matchProfile) {
		Set<Long> refIds = idRefsOf(condition);
		if (refIds.isEmpty() || matchProfile.interestIds().isEmpty()) {
			return Evaluation.unknown();
		}
		return refIds.stream().anyMatch(matchProfile.interestIds()::contains)
			? Evaluation.match("관심 지원분야와 일치")
			: Evaluation.mismatch("관심 지원분야와 다름");
	}

	/**
	 * 지원 제한.
	 *
	 * <p>다른 유형과 <b>참조의 뜻이 반대</b>다. 여기 담긴 재학상태는 요구값이 아니라
	 * <b>제외 대상</b>이다("휴학생 제외" → {@code ON_LEAVE}). 사용자가 그 상태면 불충족,
	 * 아니면 충족이다. 이걸 다른 유형처럼 다루면 의미가 정확히 뒤집힌다.
	 */
	private static Evaluation evaluateRestriction(ScholarshipCondition condition, MatchProfile matchProfile) {
		Set<String> excluded = codeRefsOf(condition);
		String userStatus = matchProfile.enrollmentStatusCode();
		if (excluded.isEmpty() || userStatus == null) {
			return Evaluation.unknown();
		}
		return excluded.contains(userStatus)
			? Evaluation.mismatch("지원 제한 대상")
			: Evaluation.match("지원 제한 해당 없음");
	}

	private static Set<Long> idRefsOf(ScholarshipCondition condition) {
		if (condition.getRefs() == null) {
			return Set.of();
		}
		return condition.getRefs().stream()
				.map(ConditionRef::getRefId)
				.filter(java.util.Objects::nonNull)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private static Set<String> codeRefsOf(ScholarshipCondition condition) {
		if (condition.getRefs() == null) {
			return Set.of();
		}
		return condition.getRefs().stream()
				.map(ConditionRef::getRefCode)
				.filter(java.util.Objects::nonNull)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
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
