package com.wishconnect.domain.scholarship.util;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

/*
검색 결과 중 "이 장학금의 상세페이지가 맞는지" 를 판정한다.

여기가 이 기능에서 가장 위험한 부분이다. 잘못 고르면 엉뚱한 블로그 글의 이미지와 첨부파일이
장학금에 붙는다. 사용자 입장에서는 틀린 정보가 붙는 게 정보가 없는 것보다 나쁘다.
그래서 점수를 매기고, 확실한 것만 자동 반영한다.

가장 강한 신호는 **기관 도메인 일치**다. 공공데이터에 '홈페이지 주소'(기관 메인)가 들어 있어
검색 결과의 호스트와 비교할 수 있다. 이게 블로그·카페·뉴스 글을 걸러내는 결정적 필터다.
 */
public final class DetailPageMatcher {

	/** 이 점수 미만이면 자동 반영하지 않고 사람이 보게 남긴다. */
	public static final int AUTO_APPLY_THRESHOLD = 70;

	private static final int SCORE_SAME_HOST = 60;
	private static final int SCORE_SAME_REGISTRABLE_DOMAIN = 45;
	private static final int SCORE_TITLE_FULL = 40;
	private static final int SCORE_TITLE_PARTIAL_MAX = 30;
	private static final int SCORE_PROVIDER_IN_TITLE = 10;

	/** 상세페이지일 리 없는 곳. 도메인이 우연히 맞아도 여기면 버린다. */
	private static final List<String> BLOCKED_HOSTS = List.of(
			"blog.naver.com", "cafe.naver.com", "post.naver.com", "in.naver.com",
			"tistory.com", "brunch.co.kr", "youtube.com", "facebook.com",
			"instagram.com", "namu.wiki", "wikipedia.org");

	private DetailPageMatcher() {
	}

	/**
	 * @param candidateUrl 검색 결과 링크
	 * @param candidateTitle 검색 결과 제목
	 * @param homepageUrl 공공데이터의 기관 홈페이지(대개 기관 메인)
	 * @param scholarshipTitle 장학금명
	 * @param provider 운영기관명
	 * @return 0~100. {@link #AUTO_APPLY_THRESHOLD} 이상이면 자동 반영해도 된다고 본다.
	 */
	public static int score(String candidateUrl, String candidateTitle,
			String homepageUrl, String scholarshipTitle, String provider) {
		if (!StringUtils.hasText(candidateUrl)) {
			return 0;
		}
		String candidateHost = hostOf(candidateUrl);
		if (candidateHost == null || isBlocked(candidateHost)) {
			return 0;
		}

		int score = 0;
		String officialHost = hostOf(homepageUrl);
		if (officialHost != null) {
			if (candidateHost.equals(officialHost)) {
				score += SCORE_SAME_HOST;
			} else if (registrableDomain(candidateHost).equals(registrableDomain(officialHost))) {
				// www.foo.go.kr 과 scholarship.foo.go.kr 처럼 서브도메인만 다른 경우.
				score += SCORE_SAME_REGISTRABLE_DOMAIN;
			}
		}
		score += titleScore(candidateTitle, scholarshipTitle);
		if (StringUtils.hasText(provider) && StringUtils.hasText(candidateTitle)
				&& candidateTitle.contains(provider)) {
			score += SCORE_PROVIDER_IN_TITLE;
		}
		return Math.min(score, 100);
	}

	/**
	 * 제목이 통째로 들어 있으면 만점, 아니면 공백으로 끊은 토큰이 얼마나 겹치는지로 부분 점수를 준다.
	 * 기관 사이트는 제목에 "[공고] 2026년 ○○장학생 선발" 처럼 군더더기를 붙여서 완전일치가 드물다.
	 */
	private static int titleScore(String candidateTitle, String scholarshipTitle) {
		if (!StringUtils.hasText(candidateTitle) || !StringUtils.hasText(scholarshipTitle)) {
			return 0;
		}
		String candidate = normalize(candidateTitle);
		String target = normalize(scholarshipTitle);
		if (candidate.contains(target)) {
			return SCORE_TITLE_FULL;
		}
		String[] tokens = scholarshipTitle.trim().split("\\s+");
		if (tokens.length == 0) {
			return 0;
		}
		long matched = 0;
		for (String token : tokens) {
			// 한두 글자 토큰("및", "제")은 우연히 맞기 쉬워 세지 않는다.
			if (token.length() >= 2 && candidate.contains(normalize(token))) {
				matched++;
			}
		}
		return (int) Math.round((double) SCORE_TITLE_PARTIAL_MAX * matched / tokens.length);
	}

	private static String normalize(String value) {
		return value.replaceAll("<[^>]*>", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
	}

	private static boolean isBlocked(String host) {
		return BLOCKED_HOSTS.stream().anyMatch(host::endsWith);
	}

	/** "www.foo.go.kr" -> "foo.go.kr". go.kr·co.kr 처럼 2단계 접미사를 고려해 뒤 3개를 남긴다. */
	static String registrableDomain(String host) {
		String[] parts = host.split("\\.");
		if (parts.length <= 2) {
			return host;
		}
		String last = parts[parts.length - 1];
		String secondLast = parts[parts.length - 2];
		boolean twoLevelSuffix = "kr".equals(last)
				&& List.of("go", "co", "or", "ac", "re", "ne").contains(secondLast);
		int keep = twoLevelSuffix ? 3 : 2;
		if (parts.length <= keep) {
			return host;
		}
		return String.join(".", List.of(parts).subList(parts.length - keep, parts.length));
	}

	static String hostOf(String url) {
		if (!StringUtils.hasText(url)) {
			return null;
		}
		try {
			String normalized = url.startsWith("http") ? url : "http://" + url;
			String host = URI.create(normalized.trim()).getHost();
			return host == null ? null : host.toLowerCase(Locale.ROOT);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
