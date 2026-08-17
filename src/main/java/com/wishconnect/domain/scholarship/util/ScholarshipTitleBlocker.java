package com.wishconnect.domain.scholarship.util;

import java.util.regex.Pattern;

/**
 * 중복 후보를 좁히기 위한 제목 정규화(blocking).
 *
 * <p>모든 장학금 쌍을 LLM 에 물어보면 호출이 O(n²) 로 폭발한다. 장학금 1,000건이면 약 50만 쌍이다.
 * 그래서 먼저 규칙으로 <b>같은 공고일 가능성이 있는 것만</b> 묶고, 그 묶음만 LLM 에 넘긴다.
 *
 * <p>정규화는 "같은 장학금이 여러 번 올라올 때 달라지는 부분"을 지우는 방향으로 만들었다.
 * 실제 수집 데이터에서 관찰된 변동 요소는 학년도·학기·차수·캠퍼스 표기·괄호 주석이다.
 *
 * <pre>
 * "2026학년도 2학기 국가장학금 2차 신청 안내"  ┐
 * "2026학년도 1학기 국가장학금 1차 신청 안내"  ├→ "국가장학금신청안내"
 * "[서울C] 국가장학금 신청 안내(~8/21까지)"     ┘
 * </pre>
 *
 * <p>주의: 이 정규화는 <b>후보를 만드는 용도</b>일 뿐이다. 같은 키로 묶였다고 중복이 아니다.
 * 1학기와 2학기 공고는 별개의 모집이므로, 실제 중복 판정은 LLM 이 기간·금액까지 보고 하고
 * 최종 승인은 사람이 한다.
 */
public final class ScholarshipTitleBlocker {

	/** 학년도·연도. "2026학년도", "2026년도", "26-2학기" 의 연도 부분. */
	private static final Pattern YEAR = Pattern.compile("(20)?\\d{2}\\s*(학년도|년도|년)");

	/** 학기·차수·기수. 같은 장학금이 회차만 바꿔 다시 올라오는 부분. */
	private static final Pattern TERM = Pattern.compile(
			"\\d{1,2}\\s*(학기|차|차수|기|기수|회|회차)");

	/** 캠퍼스·소속 표기. 같은 장학금을 캠퍼스별로 따로 공고하는 경우가 있다. */
	private static final Pattern CAMPUS = Pattern.compile(
			"(서울\\s*캠퍼스|국제\\s*캠퍼스|다빈치\\s*캠퍼스|서울C|국제C|서울|국제|다빈치|통합|공통)");

	/** 대괄호·괄호 주석. "[서울C]", "(~8/21까지)", "(재공고)" 등. */
	private static final Pattern BRACKET = Pattern.compile("[\\[(（【][^\\])）】]*[\\])）】]");

	/**
	 * 공고 문서 성격을 나타내는 말꼬리. 같은 장학금인데 표현만 다른 경우가 많다.
	 *
	 * <p>기간·일정도 포함한다. "국가근로장학금 신청 안내" 와 "국가근로장학금 신청기간 안내" 처럼
	 * 같은 장학금이 말꼬리만 달라 키가 갈리는 사례가 실제로 있었다(개발 중 테스트에서 발견).
	 * 긴 표현을 먼저 지워야 "신청기간" 이 "신청"+"기간" 으로 쪼개지지 않는다.
	 */
	private static final Pattern SUFFIX = Pattern.compile(
			"(신청\\s*기간|접수\\s*기간|모집\\s*기간|지원\\s*기간|신청\\s*일정|지원\\s*일정|접수\\s*일정"
					+ "|신청\\s*안내|선발\\s*안내|모집\\s*안내|지급\\s*안내"
					+ "|신청\\s*공고|선발\\s*공고|모집\\s*공고"
					+ "|안내|공고|모집|신청|선발|기간|일정)");

	/** 공백·구두점. 마지막에 전부 제거해 표기 차이를 흡수한다. */
	private static final Pattern NOISE = Pattern.compile("[\\s\\p{Punct}·~∼〜⭐●○▶■□]");

	/** 이보다 짧아지면 구별력이 없어 후보로 묶지 않는다. */
	private static final int MIN_KEY_LENGTH = 4;

	private ScholarshipTitleBlocker() {
	}

	/**
	 * 제목에서 blocking 키를 만든다.
	 *
	 * @return 정규화된 키. 너무 짧아 구별력이 없으면 {@code null}
	 */
	public static String blockingKey(String title) {
		if (title == null || title.isBlank()) {
			return null;
		}
		String key = title;
		key = BRACKET.matcher(key).replaceAll(" ");
		key = YEAR.matcher(key).replaceAll(" ");
		key = TERM.matcher(key).replaceAll(" ");
		key = CAMPUS.matcher(key).replaceAll(" ");
		key = SUFFIX.matcher(key).replaceAll(" ");
		key = NOISE.matcher(key).replaceAll("");
		return key.length() >= MIN_KEY_LENGTH ? key : null;
	}
}
