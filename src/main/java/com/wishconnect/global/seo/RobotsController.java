package com.wishconnect.global.seo;

import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 검색로봇 수집 규칙(robots.txt).
 *
 * <p>네이버 가이드 기준으로 세 가지를 지킨다.
 * <ul>
 *   <li><b>text/plain 으로 2xx 응답</b> — HTML 로 돌려주면 규칙이 있어도 "없음(전체 허용)"으로 읽힌다.
 *       5xx 면 반대로 "전체 비허용"이 되어 사이트가 통째로 색인에서 빠진다.</li>
 *   <li><b>네이버 검색로봇(User-agent: Yeti) 허용</b> — 로봇 배제 표준은 가장 구체적인 그룹 하나만
 *       적용하므로, {@code *} 그룹과 별개로 Yeti 그룹에도 같은 규칙을 그대로 다시 적는다.</li>
 *   <li><b>Sitemap 위치 명시</b> — 크롤러가 사이트맵을 스스로 찾아가게 한다.</li>
 * </ul>
 *
 * <p>sitemap.xml 과 마찬가지로 <b>프론트 도메인에서 프록시</b>돼야 한다. robots.txt 규칙은 호스트별로
 * 적용되므로, API 도메인에만 있으면 프론트 페이지 수집에는 아무 영향이 없다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class RobotsController {

	/** 네이버 검색로봇의 User-Agent 이름. */
	private static final String NAVER_BOT = "Yeti";

	private static final Duration BROWSER_CACHE = Duration.ofHours(1);

	private final RobotsProperties robotsProperties;
	private final SitemapProperties sitemapProperties;

	@GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> robots() {
		return ResponseEntity.ok()
				.contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
				.cacheControl(CacheControl.maxAge(BROWSER_CACHE).cachePublic())
				.body(render());
	}

	/** 규칙 본문. 그룹별로 같은 내용을 반복해야 해서 한 곳에서 만들어 두 번 찍는다. */
	String render() {
		StringBuilder text = new StringBuilder(256);
		text.append("# WishConnect\n")
				.append("# 이 규칙은 파일이 놓인 호스트에만 적용된다. 프론트 도메인에서 프록시로 서빙할 것.\n\n");
		appendGroup(text, "*");
		text.append('\n');
		text.append("# 네이버 검색로봇. 표준상 가장 구체적인 그룹만 적용되므로 위 규칙을 그대로 반복한다.\n");
		appendGroup(text, NAVER_BOT);
		if (!sitemapProperties.baseUrl().isEmpty()) {
			text.append("\nSitemap: ").append(sitemapProperties.baseUrl()).append("/sitemap.xml\n");
		}
		return text.toString();
	}

	private void appendGroup(StringBuilder text, String userAgent) {
		text.append("User-agent: ").append(userAgent).append('\n')
				.append("Allow: /\n");
		for (String path : robotsProperties.disallowPaths()) {
			String trimmed = path == null ? "" : path.trim();
			if (!trimmed.isEmpty()) {
				text.append("Disallow: ").append(trimmed.startsWith("/") ? trimmed : "/" + trimmed).append('\n');
			}
		}
	}
}
