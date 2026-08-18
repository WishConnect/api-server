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

	/** 복합 ID 내부 결합 구분자(제어문자라 실제 값과 충돌하지 않는다). */
	static final String ID_SEPARATOR = "\u0001";

	/**
	 * @param code           사이트 식별 코드 (예: konkuk)
	 * @param provider       운영기관 표시명 (예: 건국대학교)
	 * @param source         raw_scholarship.source 값
	 * @param listUrl        장학공지 목록 URL
	 * @param articlePath    (artclView 계열) 게시글 링크 경로 접두. 예: /bbs/konkuk/235/
	 * @param linkPattern    (범용) 목록에서 게시글 ID를 뽑는 정규식. 그룹1(=id). 복합키는 그룹2도 사용.
	 *                       예: articleNo=(\\d+) / fnView\\('(\\d+)',\\s*'(\\d+)'\\)
	 * @param detailTemplate (범용) 상세 URL 템플릿. {id}(=그룹1), {id2}(=그룹2) 치환.
	 * @param listParam      페이지네이션 쿼리 파라미터명(기본 page)
	 * @param titleSelector  (선택) 상세 페이지 제목 CSS 선택자. 지정 시 스킨 자동추출보다 우선.
	 * @param bodySelector   (선택) 상세 페이지 본문 CSS 선택자. 지정 시 body 전체 대신 이 영역만 사용.
	 * @param maxArticles    (선택) 이 사이트에서 한 번에 수집할 최대 공지 수. 없으면 제한 없음.
	 */
	public record Site(
			String code, String provider, String source, String listUrl,
			String articlePath, String linkPattern, String detailTemplate, String listParam,
			String titleSelector, String bodySelector, String includeCategory, Integer maxArticles) {

		/**
		 * 이 게시판이 장학 전용이 아니면, 상세 페이지의 분류가 이 말을 포함할 때만 수집한다.
		 *
		 * <p>연세대는 한 게시판에 장학·학사·일반이 섞여 올라온다. 목록 URL 에 분류 필터를 걸어
		 * 뒀지만 그건 목록에만 적용되고, 상단 고정 공지 등은 필터를 무시하고 노출된다.
		 * 실제로 수집분 42건 중 18건이 셔틀버스 시간표·수강신청 안내 같은 것이었다.
		 *
		 * <p>지정하지 않으면 분류를 보지 않는다(장학 전용 게시판이 대부분이다).
		 */
		public boolean acceptsCategory(String category) {
			if (!StringUtils.hasText(includeCategory)) {
				return true;
			}
			// 분류를 못 읽었으면 거르지 않는다. 스킨이 바뀌어 못 읽게 됐을 때
			// 멀쩡한 공지까지 통째로 사라지는 쪽이 더 나쁘다.
			return !StringUtils.hasText(category) || category.contains(includeCategory);
		}

		/** 목록에서 게시글 ID를 뽑는 정규식. 지정 없으면 articlePath 기반 artclView 패턴. */
		public String effectiveLinkPattern() {
			if (StringUtils.hasText(linkPattern)) {
				return linkPattern;
			}
			return java.util.regex.Pattern.quote(articlePath) + "(\\d+)/artclView\\.do";
		}

		/**
		 * 정규식 매칭 결과를 하나의 articleId 문자열로 만든다. 그룹이 2개 이상이면
		 * 제어문자로 이어붙여 복합키를 표현한다(fnView(date, seq) 같은 게시판 대응).
		 */
		public String articleIdOf(java.util.regex.Matcher matcher) {
			if (matcher.groupCount() >= 2 && matcher.group(2) != null) {
				return matcher.group(1) + ID_SEPARATOR + matcher.group(2);
			}
			return matcher.group(1);
		}

		/** raw_scholarship.source_id 로 저장할 안정적인 문자열(멱등성 키). 복합키는 '_' 결합. */
		public String sourceIdOf(String articleId) {
			java.util.regex.Matcher articleNo = java.util.regex.Pattern.compile("articleNo=(\\d+)").matcher(articleId);
			if (articleNo.find()) {
				return articleNo.group(1);
			}
			java.util.regex.Matcher slug = java.util.regex.Pattern.compile("[?&]slug=([^&]+)").matcher(articleId);
			String sourceId = slug.find() ? slug.group(1) : articleId.replace(ID_SEPARATOR, "_");
			return sourceId.length() > 180 ? sha256(sourceId) : sourceId;
		}

		/** 상세 URL을 만든다. articleId 가 실제 href 면 그대로 보정하고, 아니면 detailTemplate/artclView 규칙을 쓴다. */
		public String detailUrl(String baseUrl, String articleId) {
			if (articleId.startsWith("http://") || articleId.startsWith("https://")) {
				return articleId;
			}
			if (articleId.startsWith("/")) {
				return baseUrl + articleId;
			}
			if (articleId.startsWith("?")) {
				return baseUrl + java.net.URI.create(listUrl).getPath() + articleId;
			}
			String[] parts = articleId.split(ID_SEPARATOR, -1);
			if (StringUtils.hasText(detailTemplate)) {
				String url = detailTemplate.replace("{id}", parts[0]);
				if (parts.length > 1) {
					url = url.replace("{id2}", parts[1]);
				}
				return url;
			}
			return baseUrl + articlePath + parts[0] + "/artclView.do";
		}

		/** 목록에서 게시글 링크를 걸러낼 CSS 선택자 힌트(넓게 a[href] 후 정규식 필터). */
		public String listPageUrl(int page) {
			String param = StringUtils.hasText(listParam) ? listParam : "page";
			int value = "article.offset".equals(param) ? Math.max(page - 1, 0) * 10 : page;
			String sep = listUrl.contains("?") ? "&" : "?";
			return listUrl + sep + param + "=" + value;
		}
	}

	private static String sha256(String value) {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			return java.util.HexFormat.of().formatHex(
					digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 64);
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 사용 불가", e);
		}
	}

	public List<Site> sitesOrEmpty() {
		return sites == null ? List.of() : sites;
	}
}
