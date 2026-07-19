package com.wishconnect.domain.scholarship.collector;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 대학 장학공지 게시판 수집 대상 설정. 같은 CMS(artclView.do 패턴)를 쓰는 대학은
 * yml에 사이트 한 줄만 추가하면 수집된다.
 *
 * @param sites 수집 대상 대학 목록
 */
@ConfigurationProperties(prefix = "scholarship.collect.univ")
public record UnivNoticeProperties(List<Site> sites) {

	/**
	 * @param code        사이트 식별 코드 (예: konkuk)
	 * @param provider    운영기관 표시명 (예: 건국대학교)
	 * @param source      raw_scholarship.source 값 (기존 데이터 호환을 위해 명시)
	 * @param listUrl     장학공지 목록 URL (subview.do)
	 * @param articlePath 게시글 링크 경로 접두 (예: /bbs/konkuk/235/)
	 */
	public record Site(String code, String provider, String source, String listUrl, String articlePath) {
	}

	public List<Site> sitesOrEmpty() {
		return sites == null ? List.of() : sites;
	}
}
