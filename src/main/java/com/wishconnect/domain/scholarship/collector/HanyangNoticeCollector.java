package com.wishconnect.domain.scholarship.collector;

import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.dto.CollectResultResponse;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
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
import java.util.Map;
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
한양대학교 장학공지 수집기입니다.

한양대는 Liferay 포틀릿 기반이라 URL 이 길고, 목록·상세가 모두 같은 경로에
포틀릿 파라미터(action=view / view_message)로 갈립니다.

이 학교의 장점은 상세에 구조화된 메타 필드가 있다는 점입니다.
- 공지분류: 장학/등록, 모집/채용 등 → 유형 판정에 직접 사용
- 행사기간: "2026. 8. 13 ~ 2026. 8. 30" → 신청기간을 본문 정규식 추정 없이 확보
행사기간이 비어 있는 공지만 본문 정규식으로 보완합니다.

raw_scholarship(source=UNIV_HANYANG)에 원본 보존 후 scholarship 으로 정제합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HanyangNoticeCollector {

	static final String SOURCE = "UNIV_HANYANG";
	private static final String PROVIDER = "한양대학교";
	private static final String BASE_URL = "https://www.hanyang.ac.kr";
	private static final String BOARD_PATH = "/notice_all";

	/** Liferay 포틀릿 식별 파라미터. 목록·상세 공통. */
	private static final String PORTLET_PARAMS =
			"p_p_id=kr_ac_hanyang_noticeBoard_web_portlet_NoticeBoardPortlet"
					+ "&p_p_lifecycle=0&p_p_state=normal&p_p_mode=view";
	/** 포틀릿 네임스페이스. 모든 커스텀 파라미터에 이 접두가 붙는다. */
	private static final String NS = "_kr_ac_hanyang_noticeBoard_web_portlet_NoticeBoardPortlet_";
	/** 공지분류 '장학/등록' 카테고리 ID. 학사·모집 등 다른 분류는 수집 대상이 아니다. */
	private static final String SCHOLARSHIP_CATEGORY_ID = "224311300";

	/** 목록 링크에서 상세 식별자를 뽑는다: ...entryId=113701 */
	private static final Pattern ENTRY_LINK = Pattern.compile("entryId=(\\d+)");

	/** 메타의 행사기간 표기: "2026. 8. 13 ~ 2026. 8. 30" */
	private static final Pattern META_PERIOD = Pattern.compile(
			"(20\\d{2})\\s*\\.\\s*(\\d{1,2})\\s*\\.\\s*(\\d{1,2})\\s*~\\s*"
					+ "(20\\d{2})\\s*\\.\\s*(\\d{1,2})\\s*\\.\\s*(\\d{1,2})");

	/** 본문 폴백용: "8.3.(월) 10시 ~ 8.10.(월) 15시" 처럼 시작·종료가 모두 있는 표기. */
	private static final Pattern PERIOD_RANGE = Pattern.compile(
			"(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ "(?:\\([^)]*\\))?[^~]{0,30}~\\s*"
					+ "(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ "(?:\\([^)]*\\))?\\s*(?:\\d{1,2}\\s*(?:시|:)\\s*\\d{0,2})?");
	/** 본문 폴백용: "~ 8월 30일까지" 처럼 종료일만 있는 표기. */
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
	private static final Pattern NON_POSTER = Pattern.compile(
			"(?i)logo|icon|btn|banner|common|header|footer|blank|bullet");

	private static final Pattern WORK_STUDY_KEYWORD = Pattern.compile("(국가근로|교내근로|일반근로|근로장학)");
	private static final Pattern EXTERNAL_TAG = Pattern.compile("\\[(교외|학교추천|국가|정부초청)]");
	private static final Pattern PROVIDER_IN_TITLE = Pattern.compile(
			"([가-힣A-Za-z0-9·()]+(?:장학재단|장학회|문화재단|복지재단|공익재단|인재육성재단|진흥원|동문회|위원회))");
	/** 제목 앞 캠퍼스 태그([서울]/[ERICA]). 정제 시 유지하되 분류에는 영향을 주지 않는다. */
	private static final Pattern CAMPUS_TAG = Pattern.compile("^\\[(서울|ERICA)]\\s*");

	private static final int TIMEOUT_MS = 15_000;
	private static final String USER_AGENT = "Mozilla/5.0 (WishConnect scholarship collector)";
	private static final int MAX_DOCUMENTS = 12;

	private final RawScholarshipRepository rawScholarshipRepository;
	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final ScholarshipDocumentRepository scholarshipDocumentRepository;
	private final ImageStorageService imageStorageService;

	/**
	 * 한양대 장학/등록 공지를 수집한다.
	 *
	 * @param pages 조회할 목록 페이지 수 (1 이상)
	 */
	@Transactional
	public CollectResultResponse collect(int pages) {
		int fetched = 0;
		int saved = 0;
		int skipped = 0;

		for (String entryId : fetchEntryIds(Math.max(pages, 1))) {
			fetched++;
			if (rawScholarshipRepository.existsBySourceAndSourceId(SOURCE, entryId)) {
				continue;
			}
			try {
				if (collectArticle(entryId)) {
					saved++;
				} else {
					skipped++;
				}
			} catch (Exception e) {
				log.warn("[HanyangCollector] 공지 수집 실패 entryId={} : {}", entryId, e.getMessage());
			}
		}

		log.info("[HanyangCollector] 수집 완료 목록={} 신규정제={} 스킵(마감/중복)={}", fetched, saved, skipped);
		return new CollectResultResponse(SOURCE, fetched, saved, skipped);
	}

	private List<String> fetchEntryIds(int pages) {
		LinkedHashSet<String> entryIds = new LinkedHashSet<>();
		for (int page = 1; page <= pages; page++) {
			try {
				Document doc = Jsoup.connect(listUrl(page))
						.userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
				List<String> pageIds = extractEntryIds(doc);
				if (pageIds.isEmpty()) {
					break;
				}
				entryIds.addAll(pageIds);
			} catch (Exception e) {
				log.warn("[HanyangCollector] 목록 조회 실패 page={} : {}", page, e.getMessage());
				break;
			}
		}
		return List.copyOf(entryIds);
	}

	static String listUrl(int page) {
		StringBuilder url = new StringBuilder(BASE_URL).append(BOARD_PATH).append('?')
				.append(PORTLET_PARAMS)
				.append('&').append(NS).append("action=view")
				.append('&').append(NS).append("sCategoryId=").append(SCHOLARSHIP_CATEGORY_ID);
		if (page > 1) {
			url.append('&').append(NS).append("cur=").append(page);
		}
		return url.toString();
	}

	static String detailUrl(String entryId) {
		return BASE_URL + BOARD_PATH + '?' + PORTLET_PARAMS
				+ '&' + NS + "action=view_message"
				+ '&' + NS + "entryId=" + entryId;
	}

	/** 목록 문서에서 entryId 를 순서대로 수집한다. */
	static List<String> extractEntryIds(Document doc) {
		List<String> entryIds = new ArrayList<>();
		for (Element anchor : doc.select("a[href*=entryId]")) {
			Matcher matcher = ENTRY_LINK.matcher(anchor.attr("href"));
			if (matcher.find() && !entryIds.contains(matcher.group(1))) {
				entryIds.add(matcher.group(1));
			}
		}
		return entryIds;
	}

	/** @return true = 정제 저장됨, false = 마감 등으로 SKIPPED */
	private boolean collectArticle(String entryId) throws Exception {
		String detailUrl = detailUrl(entryId);
		Document doc = Jsoup.connect(detailUrl).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
		Element view = doc.selectFirst(".noticeBoard-view-message");
		if (view == null) {
			log.warn("[HanyangCollector] 상세 영역을 찾지 못했습니다. entryId={}", entryId);
			return false;
		}

		String title = extractTitle(view);
		String bodyText = extractBody(view);
		String category = metaValue(view, "공지분류");

		Period period = parsePeriod(view, title + " " + bodyText, LocalDate.now().getYear());
		boolean closed = period != null && period.end() != null
				&& period.end().isBefore(LocalDateTime.now());

		RawScholarship raw = RawScholarship.builder()
				.source(SOURCE)
				.sourceId(entryId)
				.sourceUrl(detailUrl)
				.rawJson(Map.of(
						"title", title,
						"category", category,
						"eventPeriod", metaValue(view, "행사기간"),
						"period", period == null ? "" : period.toString()))
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
		boolean isNewScholarship = scholarship == null;
		Classification classification = classify(title, category);
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
			storePoster(view, scholarship, title);
		}
		return true;
	}

	/** 제목은 상세 영역의 h4 에 들어간다. */
	static String extractTitle(Element view) {
		Element h4 = view.selectFirst("h4");
		if (h4 != null && !h4.text().isBlank()) {
			return h4.text().trim().replaceAll("\\s+", " ");
		}
		Element h3 = view.selectFirst("h3");
		return h3 == null ? "" : h3.text().trim().replaceAll("\\s+", " ");
	}

	/**
	 * 본문 추출. 상세 영역에는 제목·메타·본문이 함께 있어 통째로 쓰되,
	 * 메타 블록만 제거해 본문 비중을 높인다.
	 */
	static String extractBody(Element view) {
		Element copy = view.clone();
		copy.select(".hyu-meta-container").remove();
		return copy.text().replaceAll("\\s+", " ").trim();
	}

	/**
	 * 메타 항목 값을 라벨로 찾는다. 각 항목은 span 두 개(라벨, 값) 구조다.
	 * 예: {@code <div class="hyu-meta-item"><span>공지분류</span><span>장학/등록</span></div>}
	 */
	static String metaValue(Element view, String label) {
		for (Element item : view.select(".hyu-meta-item")) {
			var spans = item.select("span");
			if (spans.size() >= 2 && spans.get(0).text().trim().equals(label)) {
				return spans.get(1).text().trim();
			}
		}
		return "";
	}

	/**
	 * 신청기간을 결정한다.
	 * <ol>
	 *   <li>메타의 '행사기간'을 우선 사용한다. 학교가 직접 입력한 값이라 가장 정확하다.</li>
	 *   <li>없으면 제목·본문에서 정규식으로 추출한다.</li>
	 * </ol>
	 * 공지기간은 게시 노출 기간이라 신청기간과 다르므로 사용하지 않는다.
	 */
	static Period parsePeriod(Element view, String text, int defaultYear) {
		Period fromMeta = parseMetaPeriod(metaValue(view, "행사기간"));
		if (fromMeta != null) {
			return fromMeta;
		}
		return parseTextPeriod(text, defaultYear);
	}

	/** "2026. 8. 13 ~ 2026. 8. 30" 형태의 메타 값을 파싱한다. */
	static Period parseMetaPeriod(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		Matcher matcher = META_PERIOD.matcher(value);
		if (!matcher.find()) {
			return null;
		}
		try {
			LocalDate start = LocalDate.of(Integer.parseInt(matcher.group(1)),
					Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
			LocalDate end = LocalDate.of(Integer.parseInt(matcher.group(4)),
					Integer.parseInt(matcher.group(5)), Integer.parseInt(matcher.group(6)));
			return new Period(start.atStartOfDay(), end.atTime(LocalTime.of(23, 59, 59)));
		} catch (Exception e) {
			return null;
		}
	}

	/** 메타에 기간이 없을 때 쓰는 본문 폴백. */
	static Period parseTextPeriod(String text, int defaultYear) {
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

	/** 본문 이미지에서 포스터 후보를 찾아 S3 에 저장한다(실패해도 수집 계속). */
	private void storePoster(Element view, Scholarship scholarship, String title) {
		String posterUrl = findPosterUrl(view);
		if (posterUrl == null) {
			return;
		}
		imageStorageService.storeFromUrl(posterUrl, "scholarship/hanyang",
				ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarship.getId(), title);
	}

	static String findPosterUrl(Element view) {
		for (Element img : view.select("img[src]")) {
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
	 * 장학 유형을 분류한다. 한양대는 메타의 공지분류가 있지만 '장학/등록'으로 뭉뚱그려져 있어
	 * 교내·교외 구분은 제목으로 판정한다.
	 * <ol>
	 *   <li>근로장학은 성격이 달라 WORK_STUDY 로 우선 분리한다.</li>
	 *   <li>제목에 외부 재단명이 있거나 [교외] 태그가 있으면 EXTERNAL 로 본다.</li>
	 *   <li>그 외는 INTERNAL 이다.</li>
	 * </ol>
	 */
	static Classification classify(String title, String category) {
		if (WORK_STUDY_KEYWORD.matcher(title).find() || category.contains("근로")) {
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

	/** 캠퍼스 태그([서울]/[ERICA])는 학생에게 의미가 있어 유지하고 공백만 정리한다. */
	static String cleanTitle(String title) {
		String cleaned = title.replaceAll("\\s+", " ").trim();
		return cleaned.length() > 490 ? cleaned.substring(0, 490) : cleaned;
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
