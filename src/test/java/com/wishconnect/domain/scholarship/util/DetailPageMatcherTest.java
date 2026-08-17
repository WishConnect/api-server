package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 검색 결과가 "이 장학금의 상세페이지가 맞는지" 판정하는 규칙.
 *
 * <p>여기가 틀리면 엉뚱한 블로그 글의 이미지·첨부파일이 장학금에 붙는다.
 * 사용자에게는 틀린 정보가 붙는 게 정보가 없는 것보다 나쁘므로, 임계값 근처를 특히 촘촘히 고정한다.
 */
class DetailPageMatcherTest {

	private static final String TITLE = "미래인재 성장 장학금";
	private static final String PROVIDER = "강원장학재단";
	private static final String HOMEPAGE = "http://www.gwgs.go.kr";

	private int score(String url, String title) {
		return DetailPageMatcher.score(url, title, HOMEPAGE, TITLE, PROVIDER);
	}

	@Test
	@DisplayName("기관 도메인이 같고 제목도 맞으면 자동 반영 임계값을 넘는다")
	void sameHostAndTitlePasses() {
		int score = score("http://www.gwgs.go.kr/board/view.do?id=10", "2026 미래인재 성장 장학금 선발 공고");
		assertThat(score).isGreaterThanOrEqualTo(DetailPageMatcher.AUTO_APPLY_THRESHOLD);
	}

	/** www.foo.go.kr 과 scholarship.foo.go.kr 은 같은 기관이다. */
	@Test
	@DisplayName("서브도메인만 달라도 같은 기관으로 인정한다")
	void subdomainStillCountsAsSameOrganization() {
		int score = score("https://scholarship.gwgs.go.kr/notice/10", "미래인재 성장 장학금 안내");
		assertThat(score).isGreaterThanOrEqualTo(DetailPageMatcher.AUTO_APPLY_THRESHOLD);
	}

	/** 도메인이 우연히 맞아도 블로그면 상세페이지가 아니다. */
	@Test
	@DisplayName("블로그·카페는 제목이 완벽히 맞아도 0점")
	void blogIsAlwaysRejected() {
		assertThat(score("https://blog.naver.com/someone/123", TITLE)).isZero();
		assertThat(score("https://cafe.naver.com/abc/456", TITLE)).isZero();
		assertThat(score("https://ko.wikipedia.org/wiki/장학금", TITLE)).isZero();
	}

	@Test
	@DisplayName("기관 도메인이 다르면 제목이 맞아도 자동 반영하지 않는다")
	void differentOrganizationDoesNotPass() {
		int score = score("https://www.other-foundation.or.kr/view/1", "미래인재 성장 장학금");
		assertThat(score).isLessThan(DetailPageMatcher.AUTO_APPLY_THRESHOLD);
	}

	@Test
	@DisplayName("도메인만 같고 제목이 전혀 다르면 자동 반영하지 않는다")
	void sameHostButUnrelatedTitleDoesNotPass() {
		int score = score("http://www.gwgs.go.kr/about/greeting", "이사장 인사말");
		assertThat(score).isLessThan(DetailPageMatcher.AUTO_APPLY_THRESHOLD);
	}

	@Test
	@DisplayName("링크가 없으면 0점")
	void blankUrlIsZero() {
		assertThat(score(null, TITLE)).isZero();
		assertThat(score("", TITLE)).isZero();
	}

	@Test
	@DisplayName("검색 결과 제목의 <b> 강조 태그는 판정에 영향을 주지 않는다")
	void ignoresHighlightTags() {
		int withTag = score("http://www.gwgs.go.kr/v/1", "2026 <b>미래인재 성장 장학금</b> 공고");
		int withoutTag = score("http://www.gwgs.go.kr/v/1", "2026 미래인재 성장 장학금 공고");
		assertThat(withTag).isEqualTo(withoutTag);
	}

	@Test
	@DisplayName("go.kr 같은 2단계 접미사를 도메인으로 오인하지 않는다")
	void handlesTwoLevelSuffix() {
		assertThat(DetailPageMatcher.registrableDomain("www.gwgs.go.kr")).isEqualTo("gwgs.go.kr");
		assertThat(DetailPageMatcher.registrableDomain("sub.foo.co.kr")).isEqualTo("foo.co.kr");
		assertThat(DetailPageMatcher.registrableDomain("www.example.com")).isEqualTo("example.com");
	}
}
