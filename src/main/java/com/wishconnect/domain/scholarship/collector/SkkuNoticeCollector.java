package com.wishconnect.domain.scholarship.collector;

import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.dto.CollectResultResponse;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.util.ScholarshipDedupKey;
import com.wishconnect.domain.scholarship.entity.ScholarshipDocument;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
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
성균관대학교 장학공지 수집기입니다.

성균관대는 공지 게시판을 카테고리별 URL 로 분리해 두었고(notice06.do = 장학),
목록·상세 모두 GET 쿼리파라미터로 접근됩니다. 다만 상세 본문에 두 가지 특이점이 있습니다.

1) 본문이 "게시글 내용" 라벨 뒤에 **HTML 이 이스케이프된 문자열**로 들어 있어,
   jsoup 으로 한 번 파싱한 텍스트를 다시 파싱해야 실제 내용이 나온다.
2) 목록 1페이지에 상단고정(TOP) 공지가 섞여 있어 2페이지와 일부 중복된다.
   → 수집 단계에서 LinkedHashSet 으로 중복을 제거한다.

raw_scholarship(source=UNIV_SKKU)에 원본 보존 후 scholarship 으로 정제합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkkuNoticeCollector {

	static final String SOURCE = "UNIV_SKKU";
	private static final String PROVIDER = "성균관대학교";
	private static final String BASE_URL = "https://www.skku.edu";
	/** 공지 게시판 중 장학 카테고리. 학사(02)·채용(05) 등과 URL 자체가 분리되어 있다. */
	private static final String BOARD_URL = BASE_URL + "/skku/campus/skk_comm/notice06.do";
	/** 한 페이지에 노출되는 게시글 수. offset 계산에 사용한다. */
	private static final int ARTICLE_LIMIT = 10;

	/** 목록 링크: ?mode=view&articleNo=138910&... → 그룹1 = articleNo */
	private static final Pattern ARTICLE_LINK = Pattern.compile("articleNo=(\\d+)");
	/** 상세 본문 시작 라벨. 이 뒤부터가 실제 공고 내용이다. */
	private static final String BODY_LABEL = "게시글 내용";

	/** "2026. 8. 1. ~ 8. 19." / "8/1(금) 10시 ~ 8/19(화) 17시" 처럼 시작·종료가 모두 있는 표기. */
	private static final Pattern PERIOD_RANGE = Pattern.compile(
			"(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ "(?:\\([^)]*\\))?[^~]{0,30}~\\s*"
					+ "(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ "(?:\\([^)]*\\))?\\s*(?:\\d{1,2}\\s*(?:시|:)\\s*\\d{0,2})?");
	/** "~8/24" / "2026년 8월 24일까지" 처럼 종료일만 있는 표기. 제목의 (~8/24) 형태도 여기서 잡힌다. */
	private static final Pattern PERIOD_DEADLINE = Pattern.compile(
			"(?:~\\s*)?(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ "(?:\\([^)]*\\))?\\s*(?:\\d{1,2}\\s*(?:시|:)\\s*\\d{0,2})?\\s*(?:까지|마감|기한)");

	private static final Pattern DOCUMENT_SECTION = Pattern.compile(
			"(?:제출\\s*서류|구비\\s*서류|제출서류|신청\\s*방법\\s*및\\s*제출서류)\\s*[:：]?\\s*(.{0,600})");
	private static final Pattern SECTION_BOUNDARY = Pattern.compile(
			"(?:\\d{1,2}\\s*[.)]\\s*)?(?:신청\\s*기한|신청\\s*기간|장학\\s*금액|문의|문의처|유의\\s*사항|합격자|선발|지원\\s*자격)");
	private static final Pattern ESSAY_DOCUMENT = Pattern.compile(
			"(자기\\s*소개서|자소서|학업\\s*계획서|전인적\\s*성장\\s*계획서|에세이|essay)", Pattern.CASE_INSENSITIVE);

	private static final Pattern IMAGE_EXT = Pattern.compile("(?i)\\.(jpe?g|png|gif|webp)(\\?.*)?$");
	/** 성균관대는 공통 UI 이미지가 /_res/skku/ 아래, 첨부 이미지가 /_attach/image/ 아래에 온다. */
	private static final Pattern NON_POSTER = Pattern.compile(
			"(?i)logo|icon|btn|banner|common|header|footer|blank|bullet|/_res/");

	private static final Pattern WORK_STUDY_KEYWORD = Pattern.compile("(국가근로|교내근로|일반근로|근로장학)");
	private static final Pattern EXTERNAL_TAG = Pattern.compile("\\[(교외|학교추천|국가|정부초청)]");
	private static final Pattern PROVIDER_IN_TITLE = Pattern.compile(
			"([가-힣A-Za-z0-9·()]+(?:장학재단|장학회|문화재단|복지재단|공익재단|인재육성재단|진흥원|동문회|위원회))");

	private static final int TIMEOUT_MS = 10_000;
	private static final String USER_AGENT = "Mozilla/5.0 (WishConnect scholarship collector)";
	private static final int MAX_DOCUMENTS = 12;

	private final RawScholarshipRepository rawScholarshipRepository;
	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final ScholarshipDocumentRepository scholarshipDocumentRepository;
	private final ImageStorageService imageStorageService;

	/**
	 * 성균관대 장학공지를 수집한다.
	 *
	 * @param pages 조회할 목록 페이지 수 (1 이상)
	 */
	@Transactional
	public CollectResultResponse collect(int pages) {
		int fetched = 0;
		int saved = 0;
		int skipped = 0;

		for (String articleNo : fetchArticleNos(Math.max(pages, 1))) {
			fetched++;
			if (rawScholarshipRepository.existsBySourceAndSourceId(SOURCE, articleNo)) {
				continue;
			}
			try {
				if (collectArticle(articleNo)) {
					saved++;
				} else {
					skipped++;
				}
			} catch (Exception e) {
				log.warn("[SkkuCollector] 공지 수집 실패 articleNo={} : {}", articleNo, e.getMessage());
			}
		}

		log.info("[SkkuCollector] 수집 완료 목록={} 신규정제={} 스킵(마감/중복)={}", fetched, saved, skipped);
		return new CollectResultResponse(SOURCE, fetched, saved, skipped);
	}

	/**
	 * 목록에서 articleNo 를 뽑는다.
	 * 1페이지에 상단고정 공지가 섞여 뒷페이지와 중복되므로 Set 으로 걸러낸다.
	 */
	private List<String> fetchArticleNos(int pages) {
		LinkedHashSet<String> articleNos = new LinkedHashSet<>();
		for (int page = 1; page <= pages; page++) {
			try {
				List<String> pageNos = extractArticleNos(requestListPage(page));
				if (pageNos.isEmpty()) {
					break;
				}
				articleNos.addAll(pageNos);
			} catch (Exception e) {
				log.warn("[SkkuCollector] 목록 조회 실패 page={} : {}", page, e.getMessage());
				break;
			}
		}
		return List.copyOf(articleNos);
	}

	private Document requestListPage(int page) throws Exception {
		String url = BOARD_URL + "?mode=list&articleLimit=" + ARTICLE_LIMIT
				+ "&article.offset=" + ((page - 1) * ARTICLE_LIMIT);
		return Jsoup.connect(url).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
	}

	/** 목록 문서에서 상세 링크의 articleNo 를 순서대로 수집한다. */
	static List<String> extractArticleNos(Document doc) {
		List<String> articleNos = new ArrayList<>();
		for (Element anchor : doc.select("a[href*=mode=view]")) {
			Matcher matcher = ARTICLE_LINK.matcher(anchor.attr("href"));
			if (matcher.find() && !articleNos.contains(matcher.group(1))) {
				articleNos.add(matcher.group(1));
			}
		}
		return articleNos;
	}

	/** @return true = 정제 저장됨, false = 마감 등으로 SKIPPED */
	private boolean collectArticle(String articleNo) throws Exception {
		String detailUrl = detailUrl(articleNo);
		Document doc = Jsoup.connect(detailUrl).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
		String title = extractTitle(doc);
		String bodyText = extractBody(doc);

		Period period = parsePeriod(title + " " + bodyText, LocalDate.now().getYear());
		boolean closed = period != null && period.end() != null
				&& period.end().isBefore(LocalDateTime.now());

		RawScholarship raw = RawScholarship.builder()
				.source(SOURCE)
				.sourceId(articleNo)
				.sourceUrl(detailUrl)
				.rawHtml(doc.outerHtml())
				.parseStatus(closed ? ParseStatus.SKIPPED : ParseStatus.PENDING)
				.parseError(closed ? "모집종료일이 지난 공지입니다." : null)
				.build();

		if (closed) {
			rawScholarshipRepository.save(raw);
			return false;
		}

		String dedupKey = ScholarshipDedupKey.of(SOURCE, articleNo);
		Scholarship scholarship = scholarshipRepository.findByDedupKey(dedupKey).orElse(null);
		boolean isNewScholarship = scholarship == null;
		Classification classification = classify(title);
		if (scholarship == null) {
			scholarship = scholarshipRepository.save(Scholarship.builder()
					.title(cleanTitle(title))
					.provider(classification.provider())
					.summary(null)
					.description(bodyText.length() > 2000 ? bodyText.substring(0, 2000) : bodyText)
					.scholarshipType(classification.type())
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
		if (isNewScholarship) {
			String fullText = title + "\n" + bodyText;
			storeConditions(scholarship, fullText);
			storeDocuments(scholarship, fullText);
			storePoster(doc, scholarship, title);
		}
		return true;
	}

	static String detailUrl(String articleNo) {
		return BOARD_URL + "?mode=view&articleNo=" + articleNo;
	}

	/**
	 * 제목 추출. 상세는 table.board_view 구조이고 thead 에 제목과 "최종 수정일"이 함께 들어간다.
	 * 수정일 꼬리표는 정제 대상이라 여기서 떼어낸다.
	 */
	static String extractTitle(Document doc) {
		Element thead = doc.selectFirst(".board_view thead");
		if (thead != null && !thead.text().isBlank()) {
			return thead.text().trim()
					.replaceAll("\\s*최종\\s*수정일\\s*:?\\s*\\d{4}\\.\\d{1,2}\\.\\d{1,2}\\.?\\s*$", "")
					.replaceAll("\\s+", " ")
					.trim();
		}
		return doc.title().replaceFirst("^공지사항\\s*게시판읽기\\s*\\(\\s*", "")
				.replaceFirst("\\s*\\)\\s*\\|.*$", "").trim();
	}

	/**
	 * 본문 추출.
	 * <p>
	 * 성균관대는 "게시글 내용" 라벨 뒤에 본문 HTML 이 <b>이스케이프된 문자열</b>로 들어 있다.
	 * jsoup 이 한 번 파싱한 결과에는 {@code <div class="fr-view"><p>...} 가 그대로 텍스트로 남으므로,
	 * 그 부분을 잘라 다시 파싱해야 실제 공고 내용이 나온다.
	 */
	static String extractBody(Document doc) {
		Element box = doc.selectFirst(".board_view tbody dl.board-write-box");
		String raw = box != null ? box.text() : null;
		if (raw == null || raw.isBlank()) {
			Element td = doc.selectFirst(".board_view tbody td");
			raw = td == null ? "" : td.text();
		}

		int labelIndex = raw.indexOf(BODY_LABEL);
		String content = labelIndex >= 0 ? raw.substring(labelIndex + BODY_LABEL.length()) : raw;
		// 이스케이프되어 텍스트로 남은 HTML 을 한 번 더 파싱한다.
		return Jsoup.parse(content).text().replaceAll("\\s+", " ").trim();
	}

	/**
	 * 공지 본문에서 지원 자격 조건을 뽑아 저장한다.
	 * 숫자 구조화는 이후 조건 추출 배치가 처리하므로 여기서는 원문 문장만 남긴다.
	 */
	private void storeConditions(Scholarship scholarship, String text) {
		List<ScholarshipCondition> conditions = NoticeConditionExtractor.extract(text).stream()
				.map(extracted -> ScholarshipCondition.builder()
						.scholarship(scholarship)
						.conditionType(extracted.type())
						.operator(ConditionOperator.EQ)
						.valueString(extracted.snippet())
						.autoExtracted(false)
						.build())
				.toList();
		if (!conditions.isEmpty()) {
			scholarshipConditionRepository.saveAll(conditions);
		}
	}

	/** 제출서류 섹션이 보이면 서류명을 저장한다. 명확한 후보만 보수적으로 남긴다. */
	private void storeDocuments(Scholarship scholarship, String text) {
		List<String> documentNames = extractDocumentNames(text);
		if (documentNames.isEmpty()) {
			return;
		}
		List<ScholarshipDocument> documents = new ArrayList<>();
		for (int i = 0; i < documentNames.size(); i++) {
			String name = documentNames.get(i);
			documents.add(ScholarshipDocument.builder()
					.scholarship(scholarship)
					.name(name)
					.essay(ESSAY_DOCUMENT.matcher(name).find())
					.displayOrder(i)
					.build());
		}
		scholarshipDocumentRepository.saveAll(documents);
	}

	/** 첨부 이미지에서 포스터 후보를 찾아 S3 에 저장한다(실패해도 수집 계속). */
	private void storePoster(Document doc, Scholarship scholarship, String title) {
		String posterUrl = findPosterUrl(doc);
		if (posterUrl == null) {
			return;
		}
		imageStorageService.storeFromUrl(posterUrl, "scholarship/skku",
				ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarship.getId(), title);
	}

	/** 포스터 후보 URL. 공통 UI 이미지(/_res/)는 제외하고 첨부 이미지(/_attach/)를 우선한다. */
	static String findPosterUrl(Document doc) {
		for (Element img : doc.select(".board_view img[src]")) {
			String src = img.attr("abs:src");
			if (!src.isBlank() && IMAGE_EXT.matcher(src).find() && !NON_POSTER.matcher(src).find()) {
				return src;
			}
		}
		return null;
	}

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
			if (names.size() >= MAX_DOCUMENTS) {
				break;
			}
		}
		return List.copyOf(names);
	}

	private static String cleanDocumentName(String value) {
		return value.strip()
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
		if (value.contains("신청") && !value.contains("신청서")) {
			return false;
		}
		return value.matches(".*(신청서|동의서|증명서|확인서|추천서|계획서|소개서|자소서|성적표|성적증명"
				+ "|재학증명|가족관계|주민등록|통장|사본|보고서|평가서|서약서|지원서).*");
	}

	private RecruitmentStatus resolveStatus(Period period) {
		if (period == null) {
			return RecruitmentStatus.OPEN;
		}
		if (period.start() != null && LocalDateTime.now().isBefore(period.start())) {
			return RecruitmentStatus.UPCOMING;
		}
		return RecruitmentStatus.OPEN;
	}

	record Classification(ScholarshipType type, String provider) {
	}

	/**
	 * 공지 제목으로 장학 유형을 분류한다.
	 * <ol>
	 *   <li>근로장학은 성격이 달라 WORK_STUDY 로 우선 분리한다.</li>
	 *   <li>[교외] 태그가 있거나 제목에 외부 재단명이 있으면 EXTERNAL 로 보고 운영기관명을 추출한다.</li>
	 *   <li>그 외는 INTERNAL 이다.</li>
	 * </ol>
	 */
	static Classification classify(String title) {
		if (WORK_STUDY_KEYWORD.matcher(title).find()) {
			return new Classification(ScholarshipType.WORK_STUDY, PROVIDER);
		}
		Matcher provider = PROVIDER_IN_TITLE.matcher(title);
		if (provider.find()) {
			return new Classification(ScholarshipType.EXTERNAL, provider.group(1));
		}
		if (EXTERNAL_TAG.matcher(title).find()) {
			return new Classification(ScholarshipType.EXTERNAL, PROVIDER);
		}
		return new Classification(ScholarshipType.INTERNAL, PROVIDER);
	}

	/** 분류 태그는 유지하되 공백만 정리한다. */
	static String cleanTitle(String title) {
		String cleaned = title.replaceAll("\\s+", " ").trim();
		return cleaned.length() > 490 ? cleaned.substring(0, 490) : cleaned;
	}

	/** 텍스트에서 신청기간을 추출한다. 시작~종료가 없으면 종료일만 있는 표기도 시도한다. */
	static Period parsePeriod(String text, int defaultYear) {
		try {
			Matcher range = PERIOD_RANGE.matcher(text);
			if (range.find()) {
				int startYear = range.group(1) != null ? Integer.parseInt(range.group(1)) : defaultYear;
				LocalDate start = LocalDate.of(startYear,
						Integer.parseInt(range.group(2)), Integer.parseInt(range.group(3)));
				int endYear = range.group(4) != null ? Integer.parseInt(range.group(4)) : startYear;
				LocalDate end = LocalDate.of(endYear,
						Integer.parseInt(range.group(5)), Integer.parseInt(range.group(6)));
				if (end.isBefore(start)) {
					end = end.plusYears(1);
				}
				return new Period(start.atStartOfDay(), end.atTime(parseLastTimeOrEndOfDay(range.group())));
			}

			Matcher deadline = PERIOD_DEADLINE.matcher(text);
			if (deadline.find()) {
				int endYear = deadline.group(1) != null
						? Integer.parseInt(deadline.group(1)) : defaultYear;
				LocalDate end = LocalDate.of(endYear,
						Integer.parseInt(deadline.group(2)), Integer.parseInt(deadline.group(3)));
				return new Period(null, end.atTime(parseDeadlineTime(deadline.group())));
			}
		} catch (Exception e) {
			return null;
		}
		return null;
	}

	private static LocalTime parseDeadlineTime(String matchedText) {
		Matcher time = Pattern.compile("(\\d{1,2})\\s*(?:시|:)\\s*(\\d{0,2})").matcher(matchedText);
		if (time.find()) {
			LocalTime parsed = toTime(time.group(1), time.group(2));
			if (parsed != null) {
				return parsed;
			}
		}
		return LocalTime.of(23, 59, 59);
	}

	/** 범위 표기에서는 마지막에 나온 시각이 종료 시각이다. */
	private static LocalTime parseLastTimeOrEndOfDay(String matchedText) {
		Matcher time = Pattern.compile("(\\d{1,2})\\s*(?:시|:)\\s*(\\d{0,2})").matcher(matchedText);
		LocalTime parsed = null;
		while (time.find()) {
			LocalTime candidate = toTime(time.group(1), time.group(2));
			if (candidate != null) {
				parsed = candidate;
			}
		}
		return parsed == null ? LocalTime.of(23, 59, 59) : parsed;
	}

	private static LocalTime toTime(String hourText, String minuteText) {
		int hour = Integer.parseInt(hourText);
		int minute = minuteText == null || minuteText.isBlank() ? 0 : Integer.parseInt(minuteText);
		if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
			return LocalTime.of(hour, minute, 59);
		}
		return null;
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
