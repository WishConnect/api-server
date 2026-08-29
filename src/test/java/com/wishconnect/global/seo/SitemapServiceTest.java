package com.wishconnect.global.seo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipSitemapEntry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

@DisplayName("sitemap.xml 생성")
class SitemapServiceTest {

	private static final SitemapProperties PROPERTIES = new SitemapProperties(
			"https://wish-connect.com",
			"/scholarships/{id}",
			List.of("/", "/scholarships"),
			true,
			20_000,
			Duration.ofHours(1));

	@Test
	@DisplayName("고정 페이지와 공고 상세 URL을 프론트 도메인 기준 절대주소로 담는다")
	void includesStaticPathsAndScholarshipUrls() {
		ScholarshipRepository repository = mock(ScholarshipRepository.class);
		given(repository.findSitemapEntries(anyBoolean(), any(Pageable.class)))
				.willReturn(List.of(entry(7L, LocalDateTime.of(2026, 8, 20, 10, 30), true)));

		String xml = new SitemapService(repository, PROPERTIES).getSitemapXml();

		assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
				.contains("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")
				.contains("<loc>https://wish-connect.com/</loc>")
				.contains("<loc>https://wish-connect.com/scholarships</loc>")
				.contains("<loc>https://wish-connect.com/scholarships/7</loc>")
				// 네이버 가이드 예시와 같은 W3C Datetime(+09:00)
				.contains("<lastmod>2026-08-20T10:30:00+09:00</lastmod>")
				.endsWith("</urlset>\n");
	}

	@Test
	@DisplayName("마감된 공고는 담되 우선순위를 낮춘다")
	void marksClosedScholarshipsWithLowerPriority() {
		ScholarshipRepository repository = mock(ScholarshipRepository.class);
		given(repository.findSitemapEntries(anyBoolean(), any(Pageable.class)))
				.willReturn(List.of(
						entry(1L, LocalDateTime.of(2026, 8, 20, 10, 0), true),
						entry(2L, LocalDateTime.of(2026, 1, 5, 10, 0), false)));

		String xml = new SitemapService(repository, PROPERTIES).getSitemapXml();

		assertThat(urlBlock(xml, "/scholarships/1")).contains("<priority>0.8</priority>")
				.contains("<changefreq>weekly</changefreq>");
		assertThat(urlBlock(xml, "/scholarships/2")).contains("<priority>0.3</priority>")
				.contains("<changefreq>monthly</changefreq>");
	}

	@Test
	@DisplayName("include-closed 를 끄면 마감분을 조회 단계에서 뺀다")
	void passesIncludeClosedFlagToQuery() {
		ScholarshipRepository repository = mock(ScholarshipRepository.class);
		given(repository.findSitemapEntries(anyBoolean(), any(Pageable.class))).willReturn(List.of());
		SitemapProperties openOnly = new SitemapProperties(
				"https://wish-connect.com", "/scholarships/{id}", List.of(), false, 100, Duration.ofHours(1));

		new SitemapService(repository, openOnly).getSitemapXml();

		verify(repository).findSitemapEntries(eq(false), any(Pageable.class));
	}

	@Test
	@DisplayName("수정시각이 없으면 lastmod 없이 내보낸다")
	void omitsLastmodWhenUpdatedAtIsNull() {
		ScholarshipRepository repository = mock(ScholarshipRepository.class);
		given(repository.findSitemapEntries(anyBoolean(), any(Pageable.class)))
				.willReturn(List.of(entry(9L, null, true)));

		String xml = new SitemapService(repository, PROPERTIES).getSitemapXml();

		assertThat(urlBlock(xml, "/scholarships/9")).doesNotContain("<lastmod>");
		// 고정 페이지는 목록이 매일 갈리므로 지금 시각이 붙는다.
		assertThat(urlBlock(xml, "<loc>https://wish-connect.com/</loc>")).contains("<lastmod>");
	}

	@Test
	@DisplayName("TTL 안에서는 다시 요청해도 DB를 한 번만 훑는다")
	void reusesCacheWithinTtl() {
		ScholarshipRepository repository = mock(ScholarshipRepository.class);
		given(repository.findSitemapEntries(anyBoolean(), any(Pageable.class)))
				.willReturn(List.of(entry(1L, LocalDateTime.now(), true)));
		SitemapService service = new SitemapService(repository, PROPERTIES);

		String first = service.getSitemapXml();
		String second = service.getSitemapXml();

		assertThat(second).isEqualTo(first);
		verify(repository, times(1)).findSitemapEntries(anyBoolean(), any(Pageable.class));

		// 캐시를 비우면 다시 만든다(배치 직후 강제 갱신 경로).
		service.evictCache();
		service.getSitemapXml();
		verify(repository, times(2)).findSitemapEntries(anyBoolean(), any(Pageable.class));
	}

	@Test
	@DisplayName("base-url 끝 슬래시와 경로 앞 슬래시가 겹치거나 빠져도 정상 URL을 만든다")
	void normalizesSlashes() {
		ScholarshipRepository repository = mock(ScholarshipRepository.class);
		given(repository.findSitemapEntries(anyBoolean(), any(Pageable.class)))
				.willReturn(List.of(entry(3L, LocalDateTime.now(), true)));
		SitemapProperties sloppy = new SitemapProperties("https://wish-connect.com/", "scholarships/{id}",
				List.of("insights"), true, 100, Duration.ofHours(1));

		String xml = new SitemapService(repository, sloppy).getSitemapXml();

		assertThat(xml).contains("<loc>https://wish-connect.com/insights</loc>")
				.contains("<loc>https://wish-connect.com/scholarships/3</loc>")
				.doesNotContain("com//");
	}

	@Test
	@DisplayName("base-url 이 절대주소가 아니면 기동 시점에 막는다")
	void rejectsRelativeBaseUrl() {
		// 상대경로 URL 은 네이버가 사이트맵을 통째로 버리는데, 응답은 200 이라 몇 주 뒤에나 발견된다.
		assertThatThrownBy(() -> new SitemapProperties(
				"wish-connect.com", null, null, true, 0, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("base-url");
	}

	@Test
	@DisplayName("설정이 비어 있어도 기본값으로 채우고 URL 상한은 5만을 넘기지 않는다")
	void fallsBackToDefaults() {
		SitemapProperties empty = new SitemapProperties(null, null, null, false, 0, null);
		SitemapProperties tooMany = new SitemapProperties(null, null, null, false, 999_999, null);

		assertThat(empty.baseUrl()).isEmpty();
		assertThat(empty.scholarshipPath()).isEqualTo("/scholarships/{id}");
		assertThat(empty.staticPaths()).isEmpty();
		assertThat(empty.maxUrls()).isEqualTo(20_000);
		assertThat(empty.cacheTtl()).isEqualTo(Duration.ofHours(1));
		assertThat(tooMany.maxUrls()).isEqualTo(SitemapProperties.PROTOCOL_MAX_URLS);
	}

	/** 특정 URL 이 들어 있는 {@code <url>} 블록만 잘라낸다. */
	private static String urlBlock(String xml, String needle) {
		int start = xml.lastIndexOf("<url>", xml.indexOf(needle));
		return xml.substring(start, xml.indexOf("</url>", start));
	}

	private static ScholarshipSitemapEntry entry(Long id, LocalDateTime updatedAt, boolean active) {
		return new ScholarshipSitemapEntry() {
			@Override
			public Long getId() {
				return id;
			}

			@Override
			public LocalDateTime getUpdatedAt() {
				return updatedAt;
			}

			@Override
			public boolean getActive() {
				return active;
			}
		};
	}
}
