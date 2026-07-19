package com.wishconnect.domain.scholarship.collector;

import com.wishconnect.domain.scholarship.collector.UnivNoticeProperties.Site;
import com.wishconnect.domain.scholarship.dto.CollectResultResponse;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/*
대학 장학공지 게시판 수집기입니다. 국내 다수 대학이 쓰는 공통 CMS
(목록 subview.do → 상세 /bbs/{site}/{board}/{id}/artclView.do)를 대상으로 하며,
수집 대학은 scholarship.collect.univ.sites 설정(yml)로 관리합니다.
- raw_scholarship(source=사이트별)에 원본 보존 후 scholarship(INTERNAL)으로 정제
- 신청기간은 제목/본문의 "YYYY. M. D. ~ M. D." 패턴 추출(못 찾으면 기간 없이 저장)
- 이미 수집한 공지(source_id)는 건너뛰고, 마감 지난 공지는 SKIPPED 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnivNoticeCollector {

	/** "2026. 7. 28. ~ 8. 19." / "2026. 7. 28. ~ 2026. 8. 19." / 제목 괄호 "(7. 28. ~ 8. 19.)" */
	private static final Pattern PERIOD = Pattern.compile(
			"(?:(20\\d{2})\\s*[.년]\\s*)?(\\d{1,2})\\s*[.월]\\s*(\\d{1,2})\\s*\\.?\\s*~\\s*"
					+ "(?:(20\\d{2})\\s*[.년]\\s*)?(\\d{1,2})\\s*[.월]\\s*(\\d{1,2})");
	private static final int TIMEOUT_MS = 10_000;
	private static final String USER_AGENT = "Mozilla/5.0 (WishConnect scholarship collector)";

	private final RawScholarshipRepository rawScholarshipRepository;
	private final ScholarshipRepository scholarshipRepository;
	private final UnivNoticeProperties univNoticeProperties;
	private final ImageStorageService imageStorageService;

	/** 설정된 모든 대학을 수집한다(배치용). 사이트 간 실패는 서로 격리된다. */
	public List<CollectResultResponse> collectAll(int pages) {
		return univNoticeProperties.sitesOrEmpty().stream()
				.map(site -> {
					try {
						return collect(site, pages);
					} catch (Exception e) {
						log.warn("[UnivCollector] {} 수집 실패: {}", site.code(), e.getMessage());
						return new CollectResultResponse(site.source(), 0, 0, 0);
					}
				})
				.toList();
	}

	/** 특정 대학 코드만 수집한다(수동 트리거용). */
	public Optional<CollectResultResponse> collectByCode(String code, int pages) {
		return univNoticeProperties.sitesOrEmpty().stream()
				.filter(site -> site.code().equalsIgnoreCase(code))
				.findFirst()
				.map(site -> collect(site, pages));
	}

	@Transactional
	public CollectResultResponse collect(Site site, int pages) {
		Pattern articleLink = Pattern.compile(Pattern.quote(site.articlePath()) + "(\\d+)/artclView\\.do");
		String baseUrl = baseUrlOf(site.listUrl());
		int fetched = 0;
		int saved = 0;
		int skipped = 0;
		for (int page = 1; page <= Math.max(pages, 1); page++) {
			for (String articleId : fetchArticleIds(site, articleLink, page)) {
				fetched++;
				if (rawScholarshipRepository.existsBySourceAndSourceId(site.source(), articleId)) {
					continue;
				}
				try {
					if (collectArticle(site, baseUrl, articleId)) {
						saved++;
					} else {
						skipped++;
					}
				} catch (Exception e) {
					log.warn("[UnivCollector] {} 공지 수집 실패 articleId={} : {}",
							site.code(), articleId, e.getMessage());
				}
			}
		}
		log.info("[UnivCollector] {} 수집 완료 목록={} 신규정제={} 스킵(마감/중복)={}",
				site.code(), fetched, saved, skipped);
		return new CollectResultResponse(site.source(), fetched, saved, skipped);
	}

	private List<String> fetchArticleIds(Site site, Pattern articleLink, int page) {
		try {
			Document doc = Jsoup.connect(site.listUrl() + "?page=" + page)
					.userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
			return doc.select("a[href*=" + site.articlePath() + "]").stream()
					.map(a -> {
						Matcher m = articleLink.matcher(a.attr("href"));
						return m.find() ? m.group(1) : null;
					})
					.filter(java.util.Objects::nonNull)
					.distinct()
					.toList();
		} catch (Exception e) {
			log.warn("[UnivCollector] {} 목록 조회 실패 page={} : {}", site.code(), page, e.getMessage());
			return List.of();
		}
	}

	/** @return true = 정제 저장됨, false = 마감 등으로 SKIPPED */
	private boolean collectArticle(Site site, String baseUrl, String articleId) throws Exception {
		String detailUrl = baseUrl + site.articlePath() + articleId + "/artclView.do";
		Document doc = Jsoup.connect(detailUrl).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
		String title = extractTitle(doc);
		String bodyText = doc.body() == null ? "" : doc.body().text();

		Period period = parsePeriod(title + " " + bodyText, LocalDate.now().getYear());
		boolean closed = period != null && period.end() != null
				&& period.end().isBefore(LocalDateTime.now());

		RawScholarship raw = RawScholarship.builder()
				.source(site.source())
				.sourceId(articleId)
				.sourceUrl(detailUrl)
				.rawJson(Map.of("title", title, "period", period == null ? "" : period.toString()))
				.rawHtml(doc.outerHtml())
				.parseStatus(closed ? ParseStatus.SKIPPED : ParseStatus.PENDING)
				.parseError(closed ? "모집종료일이 지난 공지입니다." : null)
				.build();

		if (closed) {
			rawScholarshipRepository.save(raw);
			return false;
		}

		String dedupKey = sha256(site.source() + "|" + title + "|"
				+ (period == null ? "" : period.start() + "~" + period.end()));
		Scholarship scholarship = scholarshipRepository.findByDedupKey(dedupKey).orElse(null);
		boolean isNewScholarship = scholarship == null;
		if (scholarship == null) {
			scholarship = scholarshipRepository.save(Scholarship.builder()
					.title(cleanTitle(title))
					.provider(site.provider())
					.summary(null)
					.description(bodyText.length() > 2000 ? bodyText.substring(0, 2000) : bodyText)
					.scholarshipType(ScholarshipType.INTERNAL)
					.applicationStartAt(period == null ? null : period.start())
					.applicationEndAt(period == null ? null : period.end())
					.recruitmentStatus(resolveStatus(period))
					.primarySource(site.source())
					.dedupKey(dedupKey)
					.homepageUrl(detailUrl)
					.build());
		}
		raw.markParsed(scholarship);
		rawScholarshipRepository.save(raw);
		if (isNewScholarship) {
			storePoster(site, doc, scholarship, title);
		}
		return true;
	}

	/** 본문 인라인 이미지 → 이미지 첨부 순으로 포스터 후보를 찾아 S3에 저장한다(실패해도 수집 계속). */
	private void storePoster(Site site, Document doc, Scholarship scholarship, String title) {
		String posterUrl = findPosterUrl(doc);
		if (posterUrl == null) {
			return;
		}
		imageStorageService.storeFromUrl(posterUrl,
				"scholarship/" + site.code(), ImageStorageService.ENTITY_TYPE_SCHOLARSHIP,
				scholarship.getId(), title);
	}

	private static final Pattern IMAGE_EXT = Pattern.compile("(?i)\\.(jpe?g|png|gif|webp)(\\?.*)?$");
	private static final Pattern NON_POSTER = Pattern.compile("(?i)logo|icon|btn|banner|common|header|footer|blank|bullet");

	/**
	 * 공지 제목 추출. 스킨별로 위치가 달라 hidden input(#artclViewTitle, 연세/외대형) →
	 * 제목 클래스(건국/한림형) → h2 → 문서 title 순으로 시도한다.
	 */
	static String extractTitle(Document doc) {
		Element hidden = doc.selectFirst("input#artclViewTitle[value]");
		if (hidden != null && !hidden.attr("value").isBlank()) {
			return hidden.attr("value").trim();
		}
		Element titled = doc.selectFirst(".artclViewTitle, .view-title, .board-view .title");
		if (titled != null && !titled.text().isBlank()) {
			return titled.text().trim();
		}
		Element h2 = doc.selectFirst("h2");
		if (h2 != null && !h2.text().isBlank()) {
			return h2.text().trim();
		}
		return doc.title().trim();
	}

	/** 상세 문서에서 포스터 후보 URL을 찾는다. 없으면 null. */
	static String findPosterUrl(Document doc) {
		for (Element img : doc.select(".artclView img[src], .view-con img[src], article img[src], img[src]")) {
			String src = img.attr("abs:src");
			if (!src.isBlank() && IMAGE_EXT.matcher(src).find() && !NON_POSTER.matcher(src).find()) {
				return src;
			}
		}
		for (Element link : doc.select("a[href*=download.do]")) {
			String name = link.text();
			if (IMAGE_EXT.matcher(name.strip()).find()) {
				return link.attr("abs:href");
			}
		}
		return null;
	}

	private String baseUrlOf(String listUrl) {
		URI uri = URI.create(listUrl);
		return uri.getScheme() + "://" + uri.getHost();
	}

	private RecruitmentStatus resolveStatus(Period period) {
		LocalDateTime now = LocalDateTime.now();
		if (period == null) {
			return RecruitmentStatus.OPEN;
		}
		if (period.start() != null && now.isBefore(period.start())) {
			return RecruitmentStatus.UPCOMING;
		}
		return RecruitmentStatus.OPEN;
	}

	/** 제목 앞의 분류 태그([교외][등록금] 등)는 유지하되 공백을 정리한다. */
	private String cleanTitle(String title) {
		String cleaned = title.replaceAll("\\s+", " ").trim();
		return cleaned.length() > 490 ? cleaned.substring(0, 490) : cleaned;
	}

	/** 텍스트에서 신청기간을 추출한다. 연도가 생략된 쪽은 앞선 연도(없으면 defaultYear)를 따른다. */
	static Period parsePeriod(String text, int defaultYear) {
		Matcher m = PERIOD.matcher(text);
		if (!m.find()) {
			return null;
		}
		try {
			int startYear = m.group(1) != null ? Integer.parseInt(m.group(1)) : defaultYear;
			LocalDate start = LocalDate.of(startYear,
					Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
			int endYear = m.group(4) != null ? Integer.parseInt(m.group(4)) : startYear;
			LocalDate end = LocalDate.of(endYear,
					Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
			if (end.isBefore(start)) {
				end = end.plusYears(1);
			}
			return new Period(start.atStartOfDay(), end.atTime(LocalTime.of(23, 59, 59)));
		} catch (Exception e) {
			return null;
		}
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(
					digest.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 64);
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 사용 불가", e);
		}
	}

	record Period(LocalDateTime start, LocalDateTime end) {
	}
}
