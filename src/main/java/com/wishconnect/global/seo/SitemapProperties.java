package com.wishconnect.global.seo;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * sitemap.xml 생성 설정.
 *
 * <p>주소는 <b>프론트 도메인</b> 기준이다. 검색엔진에 태울 것은 API(api.wish-connect.com)가 아니라
 * 사용자가 실제로 보는 페이지이기 때문이다. 프론트 라우팅이 바뀌면 코드가 아니라 이 설정만 고친다.
 *
 * <p>네이버 서치어드바이저는 사이트맵을 받을 때 <b>모든 URL 의 도메인이 소유확인된 사이트와 같아야</b>
 * 수집한다(상대경로·다른 호스트는 아예 버린다). 그래서 {@code baseUrl} 은 반드시 절대 URL 이어야 하고,
 * www 유무까지 서치어드바이저에 등록한 호스트와 똑같이 맞춰야 한다.
 *
 * @param baseUrl          서비스(프론트) 기준 주소. 예: {@code https://wish-connect.com}
 * @param scholarshipPath  장학금 상세 경로 템플릿. {@code {id}} 를 장학금 id 로 치환한다.
 * @param staticPaths      항상 포함할 고정 경로 목록(홈·목록 등). 비워 두면 상세 URL 만 나간다.
 * @param includeClosed    마감된 공고도 담을지. 네이버는 "사이트 내 모든 URL 포함"을 권장하므로 기본은 담되,
 *                         우선순위를 낮춰 표기한다. 마감분을 색인에서 빼고 싶으면 false 로 둔다.
 * @param maxUrls          한 파일에 담을 URL 상한. 네이버·사이트맵 프로토콜 상한은 50,000 이지만
 *                         XML 문자열을 통째로 캐시하므로 t3.small(-Xmx1g) 기준으로 낮게 잡는다.
 *                         상한을 넘기면 최신 수정순으로 잘린다.
 * @param cacheTtl         생성 결과 캐시 유지 시간. 네이버는 피드 응답이 느리면 제출을 제한하므로,
 *                         크롤러가 연속 호출해도 DB 를 매번 훑지 않게 한다.
 */
@ConfigurationProperties(prefix = "app.sitemap")
public record SitemapProperties(
		String baseUrl,
		String scholarshipPath,
		List<String> staticPaths,
		boolean includeClosed,
		int maxUrls,
		Duration cacheTtl
) {

	/** 네이버 서치어드바이저의 사이트맵 용량 상한(10MB). 넘기면 제출 자체가 거부된다. */
	public static final int NAVER_MAX_BYTES = 10 * 1024 * 1024;

	/** 사이트맵 프로토콜·네이버 공통 URL 상한. */
	public static final int PROTOCOL_MAX_URLS = 50_000;

	public SitemapProperties {
		baseUrl = baseUrl == null ? "" : stripTrailingSlash(baseUrl.trim());
		if (!baseUrl.isEmpty() && !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
			// 조용히 넘어가면 사이트맵은 200 으로 잘 나가는데 네이버·구글이 통째로 버린다.
			// 색인이 안 되는 것을 몇 주 뒤에 발견하느니 기동 시점에 터뜨린다.
			throw new IllegalArgumentException(
					"app.sitemap.base-url 은 http(s):// 로 시작하는 절대 주소여야 합니다: " + baseUrl);
		}
		scholarshipPath = (scholarshipPath == null || scholarshipPath.isBlank())
				? "/scholarships/{id}" : scholarshipPath.trim();
		staticPaths = staticPaths == null ? List.of() : List.copyOf(staticPaths);
		maxUrls = maxUrls <= 0 ? 20_000 : Math.min(maxUrls, PROTOCOL_MAX_URLS);
		cacheTtl = cacheTtl == null ? Duration.ofHours(1) : cacheTtl;
	}

	/** {@code https://wish-connect.com/} 처럼 슬래시가 붙어 오면 뒤에 경로를 이어 붙일 때 {@code //} 가 된다. */
	private static String stripTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}
}
