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
 * 검색엔진용 sitemap.xml.
 *
 * <p>프론트(Next.js)가 아니라 API 서버가 내려준다. 어떤 공고가 살아 있는지는 DB 만 알기 때문이다.
 * 대신 사이트맵은 페이지와 <b>같은 호스트</b>에서 보여야 하므로, 프론트/nginx 에서
 * {@code https://wish-connect.com/sitemap.xml} → 이 엔드포인트로 프록시(rewrite)해야 한다.
 *
 * <p>API 명세에 낄 문서가 아니므로 Swagger 에서는 감춘다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class SitemapController {

	/** 크롤러가 재요청 전에 캐시를 쓰게 하는 시간. 서버 캐시(TTL)와 별개로 응답 헤더에도 실어 준다. */
	private static final Duration BROWSER_CACHE = Duration.ofHours(1);

	private final SitemapService sitemapService;

	@GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
	public ResponseEntity<String> sitemap() {
		return ResponseEntity.ok()
				// charset 을 명시하지 않으면 String 응답이 ISO-8859-1 로 나갈 수 있다.
				.contentType(new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8))
				.cacheControl(CacheControl.maxAge(BROWSER_CACHE).cachePublic())
				.body(sitemapService.getSitemapXml());
	}
}
