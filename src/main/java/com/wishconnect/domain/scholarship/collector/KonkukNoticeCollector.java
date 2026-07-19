package com.wishconnect.domain.scholarship.collector;

import com.wishconnect.domain.scholarship.dto.CollectResultResponse;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HexFormat;
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
건국대학교 장학공지 게시판 수집기(교내 장학금 PoC)입니다.
목록(/konkuk/2239/subview.do) → 상세(/bbs/konkuk/235/{id}/artclView.do)를 크롤링해
raw_scholarship(source=KONKUK_NOTICE)에 원본을 보존하고 scholarship(INTERNAL)으로 정제합니다.
- 신청기간은 제목/본문의 "YYYY. M. D. ~ M. D." 패턴에서 추출(못 찾으면 기간 없이 저장)
- 이미 수집한 공지(source_id 기준)는 건너뛴다(멱등)
- 마감 지난 공지는 SKIPPED 처리(정제 안 함) — KOSAF 파이프라인과 동일 정책
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KonkukNoticeCollector {

	public static final String SOURCE = "KONKUK_NOTICE";

	private static final String BASE_URL = "https://www.konkuk.ac.kr";
	private static final String LIST_URL = BASE_URL + "/konkuk/2239/subview.do";
	private static final Pattern ARTICLE_LINK = Pattern.compile("/bbs/konkuk/235/(\\d+)/artclView\\.do");
	/** "2026. 7. 28. ~ 8. 19." / "2026. 7. 28. ~ 2026. 8. 19." / 제목 괄호 "(7. 28. ~ 8. 19.)" */
	private static final Pattern PERIOD = Pattern.compile(
			"(?:(20\\d{2})\\s*[.년]\\s*)?(\\d{1,2})\\s*[.월]\\s*(\\d{1,2})\\s*\\.?\\s*~\\s*"
					+ "(?:(20\\d{2})\\s*[.년]\\s*)?(\\d{1,2})\\s*[.월]\\s*(\\d{1,2})");
	private static final int TIMEOUT_MS = 10_000;
	private static final String USER_AGENT = "Mozilla/5.0 (WishConnect scholarship collector)";

	private final RawScholarshipRepository rawScholarshipRepository;
	private final ScholarshipRepository scholarshipRepository;

	@Transactional
	public CollectResultResponse collect(int pages) {
		int fetched = 0;
		int saved = 0;
		int skipped = 0;
		for (int page = 1; page <= Math.max(pages, 1); page++) {
			for (String articleId : fetchArticleIds(page)) {
				fetched++;
				if (rawScholarshipRepository.existsBySourceAndSourceId(SOURCE, articleId)) {
					continue;
				}
				try {
					if (collectArticle(articleId)) {
						saved++;
					} else {
						skipped++;
					}
				} catch (Exception e) {
					log.warn("[KonkukCollector] 공지 수집 실패 articleId={} : {}", articleId, e.getMessage());
				}
			}
		}
		log.info("[KonkukCollector] 수집 완료 목록={} 신규정제={} 스킵(마감/중복)={}", fetched, saved, skipped);
		return new CollectResultResponse(SOURCE, fetched, saved, skipped);
	}

	private java.util.List<String> fetchArticleIds(int page) {
		try {
			Document doc = Jsoup.connect(LIST_URL + "?page=" + page)
					.userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
			return doc.select("a[href*=/bbs/konkuk/235/]").stream()
					.map(a -> {
						Matcher m = ARTICLE_LINK.matcher(a.attr("href"));
						return m.find() ? m.group(1) : null;
					})
					.filter(java.util.Objects::nonNull)
					.distinct()
					.toList();
		} catch (Exception e) {
			log.warn("[KonkukCollector] 목록 조회 실패 page={} : {}", page, e.getMessage());
			return java.util.List.of();
		}
	}

	/** @return true = 정제 저장됨, false = 마감 등으로 SKIPPED */
	private boolean collectArticle(String articleId) throws Exception {
		String detailUrl = BASE_URL + "/bbs/konkuk/235/" + articleId + "/artclView.do";
		Document doc = Jsoup.connect(detailUrl).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
		String title = Optional.ofNullable(doc.selectFirst("h2, .view-title, .artclViewTitle"))
				.map(Element::text).orElse(doc.title()).trim();
		String bodyText = doc.body() == null ? "" : doc.body().text();

		Period period = parsePeriod(title + " " + bodyText, LocalDate.now().getYear());
		boolean closed = period != null && period.end() != null
				&& period.end().isBefore(LocalDateTime.now());

		RawScholarship raw = RawScholarship.builder()
				.source(SOURCE)
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

		String dedupKey = sha256(SOURCE + "|" + title + "|"
				+ (period == null ? "" : period.start() + "~" + period.end()));
		Scholarship scholarship = scholarshipRepository.findByDedupKey(dedupKey).orElse(null);
		if (scholarship == null) {
			scholarship = scholarshipRepository.save(Scholarship.builder()
					.title(cleanTitle(title))
					.provider("건국대학교")
					.summary(null)
					.description(bodyText.length() > 2000 ? bodyText.substring(0, 2000) : bodyText)
					.scholarshipType(ScholarshipType.INTERNAL)
					.applicationStartAt(period == null ? null : period.start())
					.applicationEndAt(period == null ? null : period.end())
					.recruitmentStatus(resolveStatus(period))
					.primarySource(SOURCE)
					.dedupKey(dedupKey)
					.homepageUrl(detailUrl)
					.build());
		}
		raw.markParsed(scholarship);
		rawScholarshipRepository.save(raw);
		return true;
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
