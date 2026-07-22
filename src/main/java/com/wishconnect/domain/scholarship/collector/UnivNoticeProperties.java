package com.wishconnect.domain.scholarship.collector;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 대학 장학공지 게시판 수집 대상 설정. 게시판 유형(URL 패턴)이 달라도 사이트별 설정으로 대응한다.
 * <ul>
 *   <li>artclView 계열(건국/한림/연세/외대): articlePath 만 주면 됨(기존 호환)</li>
 *   <li>쿼리파라미터 계열(성균관/홍익/세종 등): linkPattern + detailTemplate 지정</li>
 * </ul>
 *
 * @param sites 수집 대상 대학 목록
 */
@ConfigurationProperties(prefix = "scholarship.collect.univ")
public record UnivNoticeProperties(List<Site> sites) {

	/**
	 * @param code           사이트 식별 코드 (예: konkuk)
	 * @param provider       운영기관 표시명 (예: 건국대학교)
	 * @param source         raw_scholarship.source 값
	 * @param listUrl        장학공지 목록 URL
	 * @param articlePath    (artclView 계열) 게시글 링크 경로 접두. 예: /bbs/konkuk/235/
	 * @param linkPattern    (범용) 목록에서 게시글 ID를 뽑는 정규식(그룹1=id). 예: articleNo=(\\d+)
	 * @param detailTemplate (범용) 상세 URL 템플릿. {id} 치환. 예: https://.../notice.do?mode=view&articleNo={id}
	 * @param listParam      페이지네이션 쿼리 파라미터명(기본 page)
	 */
	public record Site(
			String code, String provider, String source, String listUrl,
			String articlePath, String linkPattern, String detailTemplate, String listParam) {

		/** 목록에서 게시글 ID를 뽑는 정규식. 지정 없으면 articlePath 기반 artclView 패턴. */
		public String effectiveLinkPattern() {
			if (StringUtils.hasText(linkPattern)) {
				return linkPattern;
			}
			return java.util.regex.Pattern.quote(articlePath) + "(\\d+)/artclView\\.do";
		}

		/** 상세 URL을 만든다. detailTemplate 우선, 없으면 artclView 규칙. */
		public String detailUrl(String baseUrl, String id) {
			if (StringUtils.hasText(detailTemplate)) {
				return detailTemplate.replace("{id}", id);
			}
			return baseUrl + articlePath + id + "/artclView.do";
		}

		/** 목록에서 게시글 링크를 걸러낼 CSS 선택자 힌트(넓게 a[href] 후 정규식 필터). */
		public String listPageUrl(int page) {
			String param = StringUtils.hasText(listParam) ? listParam : "page";
			String sep = listUrl.contains("?") ? "&" : "?";
			return listUrl + sep + param + "=" + page;
		}
	}

	public List<Site> sitesOrEmpty() {
		return sites == null ? List.of() : sites;
	}
}
