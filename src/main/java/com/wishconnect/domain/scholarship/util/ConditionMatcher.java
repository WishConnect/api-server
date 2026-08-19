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
				case UNIVERSITY_TYPE -> evaluateUniversity(condition, matchProfile);
				// 프로필에 대응 필드가 없는 유형(추천서)은 중립 처리
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
	/**
	 * 대학 구분.
	 *
	 * <p>교내 공고의 자격은 대개 <b>학교 이름</b>으로 적힌다 — "인천대학교 재학생".
	 * 이 유형을 통째로 중립 처리하고 있어서, 인천대 학생이 아닌 사람에게 인천대 교내
	 * 장학금이 추천됐다. 조건이 특정 학교를 짚는데 내 학교가 아니면 불일치다.
	 *
	 * <p>"4년제", "전문대" 처럼 학교 이름이 없는 문구는 판정하지 않는다. 그건 학교가 아니라
	 * 학교의 종류를 말하는 것이고, 우리 프로필에는 대응하는 값이 없다.
	 */
	private static Evaluation evaluateUniversity(ScholarshipCondition condition, MatchProfile matchProfile) {
		if (matchProfile.profile() == null || matchProfile.profile().getSchool() == null) {
			return Evaluation.unknown();
		}
		String raw = condition.getValueString();
		if (raw == null || raw.isBlank()) {
			return Evaluation.unknown();
		}
		java.util.List<String> named = new java.util.ArrayList<>();
		java.util.regex.Matcher matcher = SCHOOL_NAME.matcher(raw);
		while (matcher.find()) {
			// "외국대학에 재학 중이지 않은" 처럼 학교를 <가리키지 않는> 말이 걸린다.
			// 이걸 학교명으로 보면 모두를 불일치로 몰아 자격 있는 사람을 떨어뜨린다.
			if (!GENERIC_SCHOOL_WORDS.contains(normalizeSchool(matcher.group()))) {
				named.add(matcher.group());
			}
		}
		if (named.isEmpty()) {
			return Evaluation.unknown();
		}
		String schoolName = matchProfile.profile().getSchool().getName();
		String mine = normalizeSchool(schoolName);
		if (mine.isBlank()) {
			return Evaluation.unknown();
		}
		// 공고가 여러 학교를 나열할 수 있다("서울대·연세대·고려대 재학생"). 하나라도 내 학교면 충족.
		boolean matched = named.stream()
				.map(ConditionMatcher::normalizeSchool)
				.anyMatch(school -> school.contains(mine) || mine.contains(school));
		return matched
				? Evaluation.match("재학 중인 학교(" + schoolName + ")")
				: Evaluation.mismatch("다른 학교 대상(" + String.join("·", named) + ")");
	}

	/**
	 * 학교 이름처럼 생겼지만 특정 학교가 아닌 말들.
	 *
	 * <p>실제 공고에서 나온 것들이다 — "외국대학에 재학 중이지 않은 대학생",
	 * "4년제(5~6년제포함)기술대학원격대학…". 이런 문구를 학교명으로 읽으면 아무도 통과하지 못한다.
	 */
	private static final java.util.Set<String> GENERIC_SCHOOL_WORDS = java.util.Set.of(
			"외국", "해외", "국내", "타", "본교", "소속", "재학", "전문", "일반", "기술", "원격",
			"사이버", "방송", "각", "산업", "교육", "특수", "폴리텍", "학점은행제");

	/** 공고에 쓰이는 학교 표기. "○○대학교"·"○○대"를 잡는다. */
	private static final java.util.regex.Pattern SCHOOL_NAME =
			java.util.regex.Pattern.compile("[가-힣]{2,10}(?:대학교|대학|대)(?=[\\s,에의)]|$)");

	/** 표기 차이(공백, "대학교"/"대")를 흡수한다. */
	private static String normalizeSchool(String name) {
		if (name == null) {
			return "";
		}
		return name.replaceAll("\\s+", "").replaceAll("(대학교|대학|대)$", "");
	}

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
		// 다른 지역명이 분명히 적혀 있으면 불일치다. 여기서 unknown 을 내면 자격 게이트가
		// MISMATCH 만 거르므로 그대로 통과한다 — 서울 사는 사람에게 울산 장학금이 추천됐다.
		//
		//   "…주민등록상 주소가 울산이며"   → 울산이 적혀 있다. 서울 거주자에게는 불일치.
		//   "관내에 주소를 두고 1년 이상"    → 어느 지역인지 알 수 없다. 판정 불가로 둔다.
		String other = firstRegionNameIn(raw);
		if (other != null) {
			return Evaluation.mismatch("거주지역 불일치(" + other + " 대상, 내 지역 "
					+ region.getName() + ")");
		}
		return Evaluation.unknown();
	}

	/**
	 * 조건 문구에 등장하는 <b>구체적인 지역명</b> 하나. 없으면 null.
	 *
	 * <p>"관내"·"도내"·"지역"처럼 어느 곳인지 알 수 없는 표현은 지역명으로 치지 않는다.
	 * 그런 문구까지 불일치로 몰면 자격이 있는 사람을 떨어뜨린다.
	 */
	private static String firstRegionNameIn(String raw) {
		java.util.regex.Matcher matcher = REGION_NAME.matcher(raw);
		return matcher.find() ? matcher.group() : null;
	}

	/**
	 * 공고에 쓰이는 지역 표기.
	 *
	 * <p>광역시·도는 이름만으로 식별되고, 시·군·구는 뒤에 단위가 붙어야 지역명인지 알 수 있다
	 * ("관악구", "군산시"). 단위 없이 두 글자만 잡으면 "본인"·"거주" 같은 말이 걸린다.
	 */
	private static final java.util.regex.Pattern REGION_NAME = java.util.regex.Pattern.compile(
			"서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주"
					+ "|[가-힣]{2,4}(?:특별시|광역시|특별자치시|특별자치도)"
					+ "|[가-힣]{2,4}(?:시|군|구)(?=[에의\\s,)]|$)");

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
