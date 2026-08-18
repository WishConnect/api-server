package com.wishconnect.domain.scholarship.collector;

import com.wishconnect.domain.scholarship.collector.UnivNoticeProperties.Site;
import com.wishconnect.domain.scholarship.dto.CollectResultResponse;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.util.NoticeHtmlExtractor;
import com.wishconnect.domain.scholarship.util.ScholarshipDedupKey;
import com.wishconnect.domain.scholarship.entity.ScholarshipDocument;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
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

	/**
	 * "2026. 7. 28. ~ 8. 19." / "2026-07-01 ~ 2026-07-31" /
	 * "8.3.월 10시~8.10.월 15시"처럼 시작일과 종료일이 모두 있는 표기.
	 */
	private static final Pattern PERIOD_RANGE = Pattern.compile(
			"(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ "(?:\\([^)]*\\))?[^~]{0,30}~\\s*"
					+ "(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ "(?:\\([^)]*\\))?\\s*(?:\\d{1,2}\\s*(?:시|:)\\s*\\d{0,2})?");
	/** "~4/16(목) 17시까지" / "2026년 8월 10일까지"처럼 종료일만 있는 표기. */
	private static final Pattern PERIOD_DEADLINE = Pattern.compile(
			"(?:~\\s*)?(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ "(?:\\([^)]*\\))?\\s*(?:\\d{1,2}\\s*(?:시|:)\\s*\\d{0,2})?\\s*(?:까지|마감|기한)");
	private static final Pattern DOCUMENT_SECTION = Pattern.compile(
			"(?:제출\\s*서류|구비\\s*서류|제출서류|신청\\s*방법\\s*및\\s*제출서류)\\s*[:：]?\\s*(.{0,600})");
	private static final Pattern SECTION_BOUNDARY = Pattern.compile(
			"(?:\\d{1,2}\\s*[.)]\\s*)?(?:신청\\s*기한|신청\\s*기간|장학\\s*금액|문의|문의처|유의\\s*사항|합격자|선발|지원\\s*자격)");
	private static final Pattern ESSAY_DOCUMENT = Pattern.compile(
			"(자기\\s*소개서|자소서|학업\\s*계획서|전인적\\s*성장\\s*계획서|에세이|essay)",
			Pattern.CASE_INSENSITIVE);
	private static final int TIMEOUT_MS = 10_000;
	private static final String USER_AGENT = "Mozilla/5.0 (WishConnect scholarship collector)";

	private final RawScholarshipRepository rawScholarshipRepository;
	private final UnivNoticeProperties univNoticeProperties;

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
		Pattern articleLink = Pattern.compile(site.effectiveLinkPattern());
		String baseUrl = baseUrlOf(site.listUrl());
		int maxArticles = site.maxArticles() == null ? Integer.MAX_VALUE : Math.max(site.maxArticles(), 0);
		int fetched = 0;
		int saved = 0;
		int skipped = 0;
		for (int page = 1; page <= Math.max(pages, 1); page++) {
			for (String articleId : fetchArticleIds(site, articleLink, page)) {
				if (fetched >= maxArticles) {
					break;
				}
				fetched++;
				if (rawScholarshipRepository.existsBySourceAndSourceId(site.source(), site.sourceIdOf(articleId))) {
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
			if (fetched >= maxArticles) {
				break;
			}
		}
		log.info("[UnivCollector] {} 수집 완료 목록={} 신규정제={} 스킵(마감/중복)={}",
				site.code(), fetched, saved, skipped);
		return new CollectResultResponse(site.source(), fetched, saved, skipped);
	}

	private List<String> fetchArticleIds(Site site, Pattern articleLink, int page) {
		try {
			Document doc = Jsoup.connect(site.listPageUrl(page))
					.userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
			return doc.select("a").stream()
					.map(a -> {
						// artclView 계열은 href 에, JS 함수형(fnView/view)은 onclick 또는 href="javascript:" 에 ID가 있다.
						Matcher m = articleLink.matcher(a.attr("href") + " " + a.attr("onclick"));
						return m.find() ? site.articleIdOf(m) : null;
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
		String detailUrl = site.detailUrl(baseUrl, articleId);
		Document doc = Jsoup.connect(detailUrl).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
		String title = extractTitle(doc, site.titleSelector());
		String bodyText = extractBody(doc, site.bodySelector());

		// 장학 아닌 공지는 여기서 걸러 낸다. 원본은 SKIPPED 로 남겨야 다음 배치가 또 받아오지 않는다.
		String category = extractCategory(doc);
		if (!site.acceptsCategory(category)) {
			rawScholarshipRepository.save(RawScholarship.builder()
					.source(site.source())
					.sourceId(site.sourceIdOf(articleId))
					.sourceUrl(detailUrl)
					.rawHtml(doc.outerHtml())
					.parseStatus(ParseStatus.SKIPPED)
					.parseError("장학 분류가 아닌 공지입니다" + (category == null ? "." : "(" + category + ")."))
					.build());
			return false;
		}

		// 마감 판정을 여기서 하지 않는다. 정규식이 연도를 못 읽어 올해로 가정하는 바람에
		// 모집 중인 공고를 마감으로 버렸다 — 한 배치에서 26건이 그렇게 되살아났다.
		// 수집기는 raw_html 만 남기고, 기간 판단은 근거를 대조하는 LLM 파싱이 맡는다.

		RawScholarship raw = RawScholarship.builder()
				.source(site.source())
				.sourceId(site.sourceIdOf(articleId))
				.sourceUrl(detailUrl)
				.rawHtml(doc.outerHtml())
				.parseStatus(ParseStatus.PENDING)
				.build();

		// 여기서 scholarship 을 만들지 않는다. 원본만 PENDING 으로 남기고 정제는 LLM 파싱이 맡는다.
		// 정규식으로 제목·기간·조건을 뽑던 코드가 LLM 과 같은 일을 두 번 하고 있었고, 품질도 나빴다.
		rawScholarshipRepository.save(raw);
		return true;
	}




	private static final Pattern IMAGE_EXT = Pattern.compile("(?i)\\.(jpe?g|png|gif|webp)(\\?.*)?$");
	private static final Pattern NON_POSTER =
			Pattern.compile("(?i)logo|icon|btn|banner|common|header|footer|blank|bullet|og_thumbnail|ssu_ogimage|favicon|sns|share|/resources/images/");

	/** 상세 본문 텍스트. bodySelector 지정 시 그 영역만, 없으면 body 전체. */
	/**
	 * 본문 텍스트. <b>못 찾으면 빈 문자열</b> — 예전처럼 페이지 전체로 폴백하지 않는다.
	 *
	 * <p>폴백이 있으면 본문 영역을 못 찾은 공지에서 사이트 메뉴가 통째로 본문 자리에 들어간다.
	 * 전수조사에서 서울시립대·세종대·국민대·동국대 48건이 그 상태였다.
	 */
	static String extractBody(Document doc, String bodySelector) {
		return NoticeHtmlExtractor.body(doc, bodySelector).orElse("");
	}

	/** titleSelector 가 지정되면 그것을 우선 사용하고, 없으면 스킨 자동추출 규칙을 따른다. */
	/**
	 * 제목. 못 찾으면 {@code <title>} 에서 사이트명을 떼어 쓰고, 그것도 없으면 빈 문자열.
	 *
	 * <p>예전에는 마지막 폴백이 {@code doc.title()} 이라, 게시판 스킨이 안 걸리면 페이지 문서
	 * 제목이 그대로 들어갔다("공지사항 공유팝업 열기 카카오 공유하기 URL 복사 팝업 닫기").
	 * 홍익대는 19건 전부가 이 값이었고, 제목이 같으니 dedupKey 까지 뭉쳤다.
	 */
	static String extractTitle(Document doc, String titleSelector) {
		return NoticeHtmlExtractor.title(doc, titleSelector)
				.orElseGet(() -> cleanDocumentTitle(doc));
	}

	/** {@code "홍익대학교 | 실제 제목"} 처럼 사이트명이 붙은 문서 제목에서 뒤쪽만 남긴다. */
	private static String cleanDocumentTitle(Document doc) {
		String raw = doc.title() == null ? "" : doc.title().trim();
		if (NoticeHtmlExtractor.looksLikeChrome(raw)) {
			return "";
		}
		int bar = raw.indexOf('|');
		return bar >= 0 && bar < raw.length() - 1 ? raw.substring(bar + 1).trim() : raw;
	}

	/**
	 * 공지 제목 추출. 스킨별로 위치가 달라 hidden input(#artclViewTitle, 연세/외대형) →
	 * 제목 클래스(건국/한림형) → h2 → 문서 title 순으로 시도한다.
	 */
	static String extractTitle(Document doc) {
		Element hidden = doc.selectFirst("input#artclViewTitle[value]");
		if (hidden != null && !hidden.attr("value").isBlank()) {
			return hidden.attr("value").trim();
		}
		Element titled0 = doc.selectFirst(".artclViewTitle, .view-title, .board-view .title, .bbs-view-title, .view_tit, .b-title");
		if (titled0 != null && !titled0.text().isBlank()) {
			// 제목 영역에 분류 라벨(<span class="hidden">분류</span><span>[교외장학금]</span>)을
			// 함께 넣는 스킨(인천대 등)이 있어, 자식 요소를 뺀 직접 텍스트를 우선한다.
			String ownText = titled0.ownText().trim();
			return ownText.isBlank() ? titled0.text().trim() : ownText;
		}
		Element og = doc.selectFirst("meta[property=og:title][content]");
		if (og != null && !og.attr("content").isBlank()) {
			// "홍익대학교 | 제목" 형태의 사이트명 접두 제거
			return og.attr("content").replaceFirst("^[^|]{1,20}\\|\\s*", "").trim();
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

	/**
	 * 상세 페이지의 분류 표기. 못 찾으면 null.
	 *
	 * <p>연세대 등이 쓰는 스킨은 제목 아래 목록에 넣어 둔다 —
	 * {@code <li class="cl"><span class="hidden">분류</span> [학사]</li>}.
	 * "분류" 라는 글자는 화면에 안 보이는 라벨이라 자식 요소를 뺀 직접 텍스트만 읽는다.
	 */
	static String extractCategory(Document doc) {
		Element item = doc.selectFirst(".detail li.cl, .view .detail li.cl");
		if (item == null) {
			return null;
		}
		String own = item.ownText().trim();
		return own.isEmpty() ? null : own;
	}

	/** 상세 문서에서 포스터 후보 URL을 찾는다. 없으면 null. */
	/** 상세 문서에서 포스터 후보 URL을 찾는다. 없으면 null. */
	public static String findPosterUrl(Document doc) {
		return NoticeHtmlExtractor.posterUrl(doc);
	}

	/** 제출서류/구비서류 섹션에서 서류명 후보를 추출한다. */
	static List<String> extractDocumentNames(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		Matcher sectionMatcher = DOCUMENT_SECTION.matcher(text.replaceAll("\\s+", " ").trim());
		if (!sectionMatcher.find()) {
			return List.of();
		}
		String section = sectionMatcher.group(1);
		Matcher boundary = SECTION_BOUNDARY.matcher(section);
		if (boundary.find()) {
			section = section.substring(0, boundary.start());
		}

		LinkedHashSet<String> names = new LinkedHashSet<>();
		for (String token : section.split("(?:[①②③④⑤⑥⑦⑧⑨⑩]|\\d{1,2}\\s*[.)]|[,/]|\\s+-\\s+|▶|▪|◾|○)")) {
			String name = cleanDocumentName(token);
			if (isDocumentName(name)) {
				names.add(name);
			}
			if (names.size() >= 12) {
				break;
			}
		}
		return List.copyOf(names);
	}

	private static String cleanDocumentName(String value) {
		return value
				.strip()
				.replaceAll("^(?:제출\\s*서류|구비\\s*서류|제출서류)\\s*[:：]?\\s*", "")
				.replaceAll("\\([^)]*해당[^)]*\\)", "")
				.replaceAll("최근\\s*\\d+\\s*개?월\\s*이내\\s*발급\\s*서류.*", "")
				.replaceAll("※.*", "")
				.replaceAll("\\s+", " ")
				.trim();
	}

	private static boolean isDocumentName(String value) {
		if (value.length() < 2 || value.length() > 80) {
			return false;
		}
		if (value.contains("신청") && !value.contains("신청서"))
			return false;

		return value.matches(".*(신청서|동의서|증명서|확인서|추천서|계획서|소개서|자소서|성적표|성적증명|재학증명|가족관계|주민등록|통장|사본|보고서|평가서|서약서).*");
	}

	private String baseUrlOf(String listUrl) {
		URI uri = URI.create(listUrl);
		return uri.getScheme() + "://" + uri.getHost();
	}


	/** 근로 대가로 지급되는 근로장학금. 태그([국가근로])와 본문 표현(국가근로장학생 모집) 모두 대응한다. */
	private static final Pattern WORK_STUDY_KEYWORD = Pattern.compile("(국가근로|교내근로|일반근로|근로장학)");
	private static final Pattern EXTERNAL_TAG = Pattern.compile("\\[(교외|학교추천|국가|국가근로|정부초청)\\]");
	private static final Pattern PROVIDER_IN_TITLE = Pattern.compile(
			"([가-힣A-Za-z0-9·]+(?:장학재단|장학회|문화재단|복지재단|공익재단|인재육성재단|진흥원|동문회|위원회))");




	/** 텍스트에서 신청기간을 추출한다. 연도가 생략된 쪽은 앞선 연도(없으면 defaultYear)를 따른다. */
	static Period parsePeriod(String text, int defaultYear) {
		Matcher m = PERIOD_RANGE.matcher(text);
		try {
			if (m.find()) {
				int startYear = m.group(1) != null ? Integer.parseInt(m.group(1)) : defaultYear;
				LocalDate start = LocalDate.of(startYear,
						Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
				int endYear = m.group(4) != null ? Integer.parseInt(m.group(4)) : startYear;
				LocalDate end = LocalDate.of(endYear,
						Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
				if (end.isBefore(start)) {
					end = end.plusYears(1);
				}
				return new Period(start.atStartOfDay(), end.atTime(parseLastTimeOrEndOfDay(m.group())));
			}

			m = PERIOD_DEADLINE.matcher(text);
			if (m.find()) {
				int endYear = m.group(1) != null ? Integer.parseInt(m.group(1)) : defaultYear;
				LocalDate end = LocalDate.of(endYear,
						Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
				return new Period(null, end.atTime(parseDeadlineTime(m.group())));
			}
		} catch (Exception e) {
			return null;
		}
		return null;
	}

	private static LocalTime parseDeadlineTime(String matchedText) {
		Matcher time = Pattern.compile("(\\d{1,2})\\s*(?:시|:)\\s*(\\d{0,2})").matcher(matchedText);
		if (time.find()) {
			int hour = Integer.parseInt(time.group(1));
			String minuteText = time.group(2);
			int minute = minuteText == null || minuteText.isBlank() ? 0 : Integer.parseInt(minuteText);
			if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
				return LocalTime.of(hour, minute, 59);
			}
		}
		return LocalTime.of(23, 59, 59);
	}

	private static LocalTime parseLastTimeOrEndOfDay(String matchedText) {
		Matcher time = Pattern.compile("(\\d{1,2})\\s*(?:시|:)\\s*(\\d{0,2})").matcher(matchedText);
		LocalTime parsed = null;
		while (time.find()) {
			int hour = Integer.parseInt(time.group(1));
			String minuteText = time.group(2);
			int minute = minuteText == null || minuteText.isBlank() ? 0 : Integer.parseInt(minuteText);
			if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
				parsed = LocalTime.of(hour, minute, 59);
			}
		}
		return parsed == null ? LocalTime.of(23, 59, 59) : parsed;
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
