package com.wishconnect.domain.scholarship.collector;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.format.DateTimeFormatter;
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
import org.springframework.web.client.RestClient;

/*
서강대학교 장학공지 수집기입니다.

서강대 홈페이지는 Nuxt(Vue) SPA 라 목록·상세가 HTML 에 없고, 화면이 내부 REST API 를 호출합니다.
그래서 HTML 파싱 대신 그 API 를 직접 호출합니다. 덕분에 신청기간을 본문 정규식으로 추정하지 않고
sdate/edate 필드에서 그대로 받아올 수 있어 다른 대학보다 정확합니다.

- 목록: GET /api/api/v1/mainKo/BbsData/boardList?pageNum={n}&pageSize={size}&bbsConfigFk=141
- 상세: GET /api/api/v1/mainKo/BbsData?pkId={pkId}
- 분류: 응답의 introTitleList 에 [교외]/[교내·국가]/[국가근로] 등이 담긴다.
- 본문: content 가 HTML 문자열이라 jsoup 으로 텍스트만 뽑아 저장한다.

raw_scholarship(source=UNIV_SOGANG)에 원본 보존 후 scholarship 으로 정제합니다.
 */
@Slf4j
@Component
public class SogangNoticeCollector {

	static final String SOURCE = "UNIV_SOGANG";
	private static final String PROVIDER = "서강대학교";
	private static final String BASE_URL = "https://www.sogang.ac.kr";
	/** 장학공지 게시판 식별자. 학사·일반 공지와 게시판이 분리되어 있어 이 값만 수집하면 된다. */
	private static final int SCHOLARSHIP_BOARD_ID = 141;
	private static final String LIST_PATH = "/api/api/v1/mainKo/BbsData/boardList";
	private static final String DETAIL_PATH = "/api/api/v1/mainKo/BbsData";
	/** 사용자가 브라우저에서 보는 게시판 주소. 정제 데이터의 homepageUrl 로 남긴다. */
	private static final String PAGE_URL = BASE_URL + "/ko/scholarship-notice";

	private static final int PAGE_SIZE = 16;
	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

	private static final Pattern DOCUMENT_SECTION = Pattern.compile(
			"(?:제출\\s*서류|구비\\s*서류|제출서류|신청\\s*방법\\s*및\\s*제출서류)\\s*[:：]?\\s*(.{0,600})");
	private static final Pattern SECTION_BOUNDARY = Pattern.compile(
			"(?:\\d{1,2}\\s*[.)]\\s*)?(?:신청\\s*기한|신청\\s*기간|장학\\s*금액|문의|문의처|유의\\s*사항|합격자|선발|지원\\s*자격)");
	private static final Pattern ESSAY_DOCUMENT = Pattern.compile(
			"(자기\\s*소개서|자소서|학업\\s*계획서|전인적\\s*성장\\s*계획서|에세이|essay)", Pattern.CASE_INSENSITIVE);

	private static final Pattern IMAGE_EXT = Pattern.compile("(?i)\\.(jpe?g|png|gif|webp)(\\?.*)?$");
	private static final Pattern NON_POSTER = Pattern.compile(
			"(?i)logo|icon|btn|banner|common|header|footer|blank|bullet|filetype");

	private static final Pattern WORK_STUDY_KEYWORD = Pattern.compile("(국가근로|교내근로|일반근로|근로장학)");
	private static final Pattern PROVIDER_IN_TITLE = Pattern.compile(
			"([가-힣A-Za-z0-9·()]+(?:장학재단|장학회|문화재단|복지재단|공익재단|인재육성재단|진흥원|동문회|위원회|재단))");

	private static final int MAX_DOCUMENTS = 12;

	private final RestClient restClient;
	private final RawScholarshipRepository rawScholarshipRepository;
	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final ScholarshipDocumentRepository scholarshipDocumentRepository;
	private final ImageStorageService imageStorageService;

	public SogangNoticeCollector(RawScholarshipRepository rawScholarshipRepository,
			ScholarshipRepository scholarshipRepository,
			ScholarshipConditionRepository scholarshipConditionRepository,
			ScholarshipDocumentRepository scholarshipDocumentRepository,
			ImageStorageService imageStorageService) {
		this.restClient = RestClient.builder().baseUrl(BASE_URL).build();
		this.rawScholarshipRepository = rawScholarshipRepository;
		this.scholarshipRepository = scholarshipRepository;
		this.scholarshipConditionRepository = scholarshipConditionRepository;
		this.scholarshipDocumentRepository = scholarshipDocumentRepository;
		this.imageStorageService = imageStorageService;
	}

	/**
	 * 서강대 장학공지를 수집한다.
	 *
	 * @param pages 조회할 목록 페이지 수 (1 이상)
	 */
	@Transactional
	public CollectResultResponse collect(int pages) {
		int fetched = 0;
		int saved = 0;
		int skipped = 0;

		for (String pkId : fetchArticleIds(Math.max(pages, 1))) {
			fetched++;
			if (rawScholarshipRepository.existsBySourceAndSourceId(SOURCE, pkId)) {
				continue;
			}
			try {
				if (collectArticle(pkId)) {
					saved++;
				} else {
					skipped++;
				}
			} catch (Exception e) {
				log.warn("[SogangCollector] 공지 수집 실패 pkId={} : {}", pkId, e.getMessage());
			}
		}

		log.info("[SogangCollector] 수집 완료 목록={} 신규정제={} 스킵(마감/중복)={}", fetched, saved, skipped);
		return new CollectResultResponse(SOURCE, fetched, saved, skipped);
	}

	/** 목록 API 를 페이지 수만큼 호출해 pkId 를 모은다. */
	private List<String> fetchArticleIds(int pages) {
		LinkedHashSet<String> ids = new LinkedHashSet<>();
		for (int page = 1; page <= pages; page++) {
			try {
				JsonNode list = requestList(page).path("data").path("list");
				if (!list.isArray() || list.isEmpty()) {
					break;
				}
				list.forEach(item -> {
					String pkId = item.path("pkId").asText(null);
					if (pkId != null && !pkId.isBlank()) {
						ids.add(pkId);
					}
				});
			} catch (Exception e) {
				log.warn("[SogangCollector] 목록 조회 실패 page={} : {}", page, e.getMessage());
				break;
			}
		}
		return List.copyOf(ids);
	}

	private JsonNode requestList(int page) {
		return restClient.get()
				.uri(uriBuilder -> uriBuilder.path(LIST_PATH)
						.queryParam("pageNum", page)
						.queryParam("pageSize", PAGE_SIZE)
						.queryParam("bbsConfigFk", SCHOLARSHIP_BOARD_ID)
						.build())
				.retrieve()
				.body(JsonNode.class);
	}

	private JsonNode requestDetail(String pkId) {
		return restClient.get()
				.uri(uriBuilder -> uriBuilder.path(DETAIL_PATH).queryParam("pkId", pkId).build())
				.retrieve()
				.body(JsonNode.class);
	}

	/** @return true = 정제 저장됨, false = 마감 등으로 SKIPPED */
	private boolean collectArticle(String pkId) {
		JsonNode data = requestDetail(pkId).path("data");
		if (data.isMissingNode() || data.isNull()) {
			log.warn("[SogangCollector] 상세 응답이 비어 있습니다. pkId={}", pkId);
			return false;
		}

		String title = data.path("title").asText("").trim();
		String contentHtml = data.path("content").asText("");
		Document contentDoc = Jsoup.parse(contentHtml, BASE_URL);
		String bodyText = contentDoc.text();

		Period period = parsePeriod(data);
		boolean closed = period != null && period.end() != null
				&& period.end().isBefore(LocalDateTime.now());
		String detailUrl = PAGE_URL;

		RawScholarship raw = RawScholarship.builder()
				.source(SOURCE)
				.sourceId(pkId)
				.sourceUrl(detailUrl)
				.rawJson(Map.of(
						"title", title,
						"sdate", data.path("sdate").asText(""),
						"edate", data.path("edate").asText(""),
						"category", categoryOf(data)))
				.rawHtml(contentHtml)
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
		Classification classification = classify(title, categoryOf(data));
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
			storePoster(contentDoc, scholarship, title);
		}
		return true;
	}

	/** introTitleList 의 첫 값(예: 교외, 교내/국가, 국가근로). 없으면 빈 문자열. */
	static String categoryOf(JsonNode data) {
		JsonNode categories = data.path("introTitleList");
		if (categories.isArray() && !categories.isEmpty()) {
			return categories.get(0).asText("");
		}
		return "";
	}

	/**
	 * 신청기간을 API 의 sdate/edate(yyyyMMdd)에서 읽는다.
	 * 다른 대학 수집기처럼 본문 정규식에 의존하지 않아도 되는 것이 서강대의 장점이다.
	 * 값이 비어 있으면 기간 없이 저장한다.
	 */
	static Period parsePeriod(JsonNode data) {
		LocalDate start = parseDate(data.path("sdate").asText(null));
		LocalDate end = parseDate(data.path("edate").asText(null));
		if (start == null && end == null) {
			return null;
		}
		return new Period(
				start == null ? null : start.atStartOfDay(),
				end == null ? null : end.atTime(LocalTime.of(23, 59, 59)));
	}

	private static LocalDate parseDate(String value) {
		if (value == null || value.isBlank() || value.length() < 8) {
			return null;
		}
		try {
			return LocalDate.parse(value.substring(0, 8), YYYYMMDD);
		} catch (Exception e) {
			return null;
		}
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

	/** 본문 HTML 의 인라인 이미지에서 포스터 후보를 찾아 S3 에 저장한다(실패해도 수집 계속). */
	private void storePoster(Document contentDoc, Scholarship scholarship, String title) {
		String posterUrl = findPosterUrl(contentDoc);
		if (posterUrl == null) {
			return;
		}
		imageStorageService.storeFromUrl(posterUrl, "scholarship/sogang",
				ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarship.getId(), title);
	}

	static String findPosterUrl(Document contentDoc) {
		for (Element img : contentDoc.select("img[src]")) {
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
	 * 장학 유형을 분류한다. 서강대는 API 가 분류값을 주므로 제목 추측보다 그 값을 우선한다.
	 * <ol>
	 *   <li>근로장학은 성격이 달라 WORK_STUDY 로 분리한다.</li>
	 *   <li>분류가 '교외'면 EXTERNAL 이며, 제목에서 운영기관명 추출을 시도한다.</li>
	 *   <li>그 외(교내·국가·학자금대출 등)는 INTERNAL 이다.</li>
	 * </ol>
	 */
	static Classification classify(String title, String category) {
		if (WORK_STUDY_KEYWORD.matcher(title).find() || category.contains("국가근로")) {
			return new Classification(ScholarshipType.WORK_STUDY, PROVIDER);
		}
		if (category.contains("교외") || category.contains("동문회") || category.contains("발전기금")) {
			Matcher provider = PROVIDER_IN_TITLE.matcher(title);
			return new Classification(ScholarshipType.EXTERNAL,
					provider.find() ? provider.group(1) : PROVIDER);
		}
		return new Classification(ScholarshipType.INTERNAL, PROVIDER);
	}

	/** 분류 태그는 유지하되 공백만 정리한다. */
	static String cleanTitle(String title) {
		String cleaned = title.replaceAll("\\s+", " ").trim();
		return cleaned.length() > 490 ? cleaned.substring(0, 490) : cleaned;
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
