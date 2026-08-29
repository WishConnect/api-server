package com.wishconnect.global.seo;

import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipSitemapEntry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * DB 의 장학금 공고를 읽어 sitemap.xml 본문을 만든다.
 *
 * <p>공고는 매일 수집 배치로 들어오고 마감되면 내려간다. 정적 파일로 두면 하루면 낡기 때문에
 * 요청 시점에 만든다. 다만 크롤러는 짧은 간격으로 여러 번 두드리고, 네이버는 피드 응답이 느리면
 * 제출 자체를 제한하므로, 만든 결과를 {@code app.sitemap.cache-ttl} 동안 들고 있다가 그대로 돌려준다.
 *
 * <p>형식은 사이트맵 프로토콜 0.9 를 따르며 네이버 서치어드바이저의 검증 조건
 * (모든 URL 이 소유확인된 도메인, 10MB 미만, 한 파일당 50,000 URL 미만)을 지킨다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SitemapService {

	/**
	 * lastmod 는 W3C Datetime. 네이버 가이드 예시({@code 2019-08-26T11:16:53+09:00})와 같은 모양으로
	 * 초까지만 낸다. {@code ISO_OFFSET_DATE_TIME} 을 그대로 쓰면 DB 가 들고 있는 마이크로초까지 붙어
	 * {@code ...T01:13:19.487652+09:00} 처럼 나가는데, 검증기에 따라 이를 걸고 넘어진다.
	 */
	private static final DateTimeFormatter LASTMOD = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

	/**
	 * {@code updatedAt} 은 {@link LocalDateTime} 이라 시간대 정보가 없다. 서버·배치·DB 가 모두
	 * 한국 시간으로 도는 서비스이므로 KST 로 해석해 오프셋을 붙인다.
	 */
	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

	private final ScholarshipRepository scholarshipRepository;
	private final SitemapProperties properties;

	/** 생성 결과 캐시. 갱신 중에도 이전 결과를 그대로 읽을 수 있게 참조만 갈아 끼운다. */
	private final AtomicReference<Snapshot> cache = new AtomicReference<>();

	/** 만들어 둔 XML 과 그 만료 시각. */
	private record Snapshot(String xml, Instant expiresAt) {
	}

	public String getSitemapXml() {
		Snapshot cached = cache.get();
		if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
			return cached.xml();
		}
		// 크롤러가 동시에 여러 건 때려도 DB 스캔은 한 번만 돌게 막는다.
		synchronized (this) {
			Snapshot current = cache.get();
			if (current != null && Instant.now().isBefore(current.expiresAt())) {
				return current.xml();
			}
			String xml = build();
			cache.set(new Snapshot(xml, Instant.now().plus(properties.cacheTtl())));
			return xml;
		}
	}

	/** 배치로 공고가 크게 바뀐 직후처럼, 다음 요청에서 즉시 다시 만들게 하고 싶을 때 쓴다. */
	public void evictCache() {
		cache.set(null);
	}

	private String build() {
		List<ScholarshipSitemapEntry> entries = scholarshipRepository.findSitemapEntries(
				properties.includeClosed(), PageRequest.of(0, properties.maxUrls()));
		if (entries.size() == properties.maxUrls()) {
			// 상한에 딱 걸렸다면 잘렸을 가능성이 높다. 이 로그가 보이기 시작하면
			// 카테고리별로 쪼개고 사이트맵 인덱스(sitemapindex)로 묶을 때가 된 것이다.
			log.warn("sitemap URL 상한({})에 도달했습니다. 오래된 공고가 누락될 수 있습니다.", properties.maxUrls());
		}

		StringBuilder xml = new StringBuilder(entries.size() * 160 + 512);
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
				.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

		String now = LocalDateTime.now().atZone(SERVICE_ZONE).format(LASTMOD);
		for (String path : properties.staticPaths()) {
			// 목록·홈은 매일 공고가 갈리므로 lastmod 를 지금으로 둔다.
			appendUrl(xml, properties.baseUrl() + normalize(path), now, "daily", "1.0");
		}
		for (ScholarshipSitemapEntry entry : entries) {
			String path = properties.scholarshipPath().replace("{id}", String.valueOf(entry.getId()));
			String lastmod = entry.getUpdatedAt() == null
					? null : entry.getUpdatedAt().atZone(SERVICE_ZONE).format(LASTMOD);
			// 마감분은 페이지가 더 바뀌지 않고 지원도 못 한다. 빼지는 않되(네이버는 전체 URL 수록을 권장)
			// 우선순위를 낮춰, 크롤러가 모집 중인 공고부터 가져가게 한다.
			boolean open = entry.getActive();
			appendUrl(xml, properties.baseUrl() + normalize(path), lastmod,
					open ? "weekly" : "monthly", open ? "0.8" : "0.3");
		}

		xml.append("</urlset>\n");
		String body = xml.toString();
		warnIfTooLarge(body, entries.size());
		return body;
	}

	/** 네이버는 10MB 이상 사이트맵을 아예 받지 않는다. 넘기기 전에 로그로 먼저 알린다. */
	private void warnIfTooLarge(String body, int urlCount) {
		int bytes = body.getBytes(StandardCharsets.UTF_8).length;
		if (bytes >= SitemapProperties.NAVER_MAX_BYTES) {
			log.warn("sitemap 용량이 {}바이트({}건)로 네이버 제출 상한(10MB)을 넘었습니다. "
					+ "app.sitemap.max-urls 를 줄이거나 사이트맵 인덱스로 쪼개야 합니다.", bytes, urlCount);
		}
	}

	private void appendUrl(StringBuilder xml, String loc, String lastmod, String changefreq, String priority) {
		xml.append("\t<url>\n\t\t<loc>").append(escape(loc)).append("</loc>\n");
		if (lastmod != null) {
			xml.append("\t\t<lastmod>").append(lastmod).append("</lastmod>\n");
		}
		xml.append("\t\t<changefreq>").append(changefreq).append("</changefreq>\n")
				.append("\t\t<priority>").append(priority).append("</priority>\n")
				.append("\t</url>\n");
	}

	/** 설정에서 슬래시를 빠뜨려도 {@code https://wish-connect.comscholarships} 가 되지 않게 한다. */
	private static String normalize(String path) {
		if (path == null || path.isBlank()) {
			return "/";
		}
		String trimmed = path.trim();
		return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
	}

	/** 사이트맵 프로토콜은 loc 의 엔티티 이스케이프를 요구한다(경로에 쿼리스트링이 섞일 때 대비). */
	private static String escape(String value) {
		return value.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&apos;");
	}
}
