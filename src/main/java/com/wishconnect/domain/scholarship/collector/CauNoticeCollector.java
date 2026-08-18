package com.wishconnect.domain.scholarship.collector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/*
중앙대학교 장학공지 수집기입니다.

중앙대 CAU Notice 는 목록·상세가 모두 HTML 에 없습니다. 페이지 골격만 내려온 뒤 화면이
내부 AJAX 엔드포인트를 POST 로 호출해 JSON 을 받아 그립니다. 그래서 jsoup 으로 게시판 페이지를
긁으면 상단 고정 공지 2건 외에는 아무것도 안 잡힙니다. 그 AJAX 를 직접 호출합니다.

- 목록: POST /ajax/FR_SVC/BBSViewList2.do  (application/x-www-form-urlencoded)
        BOARD_CATEGORY_NO=11 이 '장학' 탭이라 이 값만 넘기면 장학공지만 걸러집니다.
- 상세: POST /ajax/FR_SVC/BoardViewData.do (BBS_SEQ 로 조회, CONTENTS 가 본문 HTML)
- 분류: 목록의 CATEGORY_NM2 가 통합 / 외부 / 서울 / 다빈치 로 내려옵니다.

서강대와 달리 신청기간을 담는 필드가 없습니다. 상세의 START_DT·END_DT 는 게시글 노출기간이라
신청기간과 무관하고 대부분 비어 있어, 신청기간은 본문에서 추정합니다. 다만 본문 전체에서 첫
날짜 범위를 집으면 제출서류 유효기간·근로기간·심사일정을 신청기간으로 오인하는 사례가 많아
(실측 40건 중 4건), 신청기간 라벨 뒤쪽만 훑는 방식으로 좁혔습니다. 자세한 근거는 parsePeriod 참고.

raw_scholarship(source=UNIV_CAU)에 원본 보존 후 scholarship 으로 정제합니다.
 */
@Slf4j
@Component
public class CauNoticeCollector {

	static final String SOURCE = "UNIV_CAU";
	private static final String PROVIDER = "중앙대학교";
	private static final String BASE_URL = "https://www.cau.ac.kr";

	private static final String LIST_PATH = "/ajax/FR_SVC/BBSViewList2.do";
	private static final String DETAIL_PATH = "/ajax/FR_SVC/BoardViewData.do";

	/** CAU Notice 게시판 고정 파라미터. 화면의 hidden form 이 항상 이 값으로 요청한다. */
	private static final String MENU_ID = "100";
	private static final String SITE_NO = "2";
	private static final String BOARD_SEQ = "4";
	private static final String BOARD_TYPE = "C0301";
	/** '장학' 탭의 카테고리 번호. 학사·모집·행사 등과 게시판을 공유하므로 이 값으로 걸러낸다. */
	private static final String SCHOLARSHIP_CATEGORY_NO = "11";
	/** 장학 탭의 화면 탭 번호. 목록 API 가 탭 컨텍스트를 함께 받는다. */
	private static final String SCHOLARSHIP_TAB_NO = "5";

	private static final int PAGE_SIZE = 15;

	private static final Pattern PERIOD_RANGE = Pattern.compile(
			"(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ "(?:\\([^)]*\\))?[^~]{0,30}~\\s*"
					+ "(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ "(?:\\([^)]*\\))?\\s*(?:\\d{1,2}\\s*(?:시|:)\\s*\\d{0,2})?");
	/** "~ 8월 31일(월) 16:00 까지" 처럼 종료일만 있는 표기. */
	private static final Pattern PERIOD_DEADLINE = Pattern.compile(
			"(?:~\\s*)?(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ "(?:\\([^)]*\\))?\\s*(?:\\d{1,2}\\s*(?:시|:)\\s*\\d{0,2})?\\s*(?:까지|마감|기한)");
	private static final Pattern TIME_IN_TEXT = Pattern.compile("(\\d{1,2})\\s*(?:시|:)\\s*(\\d{0,2})");
	private static final Pattern WRITE_DATE = Pattern.compile("(20\\d{2})[.\\-/]");

	/**
	 * 신청기간을 가리키는 것이 분명한 라벨.
	 * 중앙대 공지는 제출서류 표의 서류 유효기간, 근로기간, 심사일정처럼 신청과 무관한 날짜 범위가
	 * 본문 앞쪽에 먼저 나오는 경우가 많아, 라벨 뒤를 먼저 뒤진 뒤에만 기간으로 인정한다.
	 */
	private static final Pattern STRONG_PERIOD_LABEL = Pattern.compile(
			"(신청\\s*[·ㆍ,/]?\\s*접수\\s*기간|신청\\s*기간|접수\\s*기간|모집\\s*기간|지원\\s*기간"
					+ "|공모\\s*기간|신청\\s*기한|접수\\s*기한|신청\\s*일정|접수\\s*일정|신청\\s*및\\s*접수)");
	/** 표 안에서 "신청 7.1.(수) ~ 11.17.(화)" 처럼 라벨이 한 단어로만 붙는 경우. */
	private static final Pattern WEAK_PERIOD_LABEL = Pattern.compile("(신청|접수|모집|공모)");
	private static final int STRONG_LABEL_WINDOW = 160;
	private static final int WEAK_LABEL_WINDOW = 40;

	private static final Pattern DOCUMENT_SECTION = Pattern.compile(
			"(?:제출\\s*서류|구비\\s*서류|제출서류|신청\\s*방법\\s*및\\s*제출서류)\\s*[:：]?\\s*(.{0,600})");
	private static final Pattern SECTION_BOUNDARY = Pattern.compile(
			"(?:\\d{1,2}\\s*[.)]\\s*)?(?:신청\\s*기한|신청\\s*기간|장학\\s*금액|문의|문의처|유의\\s*사항|합격자|선발|지원\\s*자격)");
	private static final Pattern ESSAY_DOCUMENT = Pattern.compile(
			"(자기\\s*소개서|자소서|학업\\s*계획서|전인적\\s*성장\\s*계획서|에세이|essay)", Pattern.CASE_INSENSITIVE);

	private static final Pattern IMAGE_EXT = Pattern.compile("(?i)\\.(jpe?g|png|gif|webp)(\\?.*)?$");
	private static final Pattern NON_POSTER = Pattern.compile(
			"(?i)logo|icon|btn|banner|common|header|footer|blank|bullet|filetype|/cau2021/");

	private static final Pattern WORK_STUDY_KEYWORD = Pattern.compile("(국가근로|교내근로|일반근로|근로장학)");
	private static final Pattern PROVIDER_IN_TITLE = Pattern.compile(
			"([가-힣A-Za-z0-9·()]+(?:장학재단|장학회|문화재단|복지재단|공익재단|인재육성재단|진흥원|동문회|위원회|재단))");

	private static final int MAX_DOCUMENTS = 12;

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final RawScholarshipRepository rawScholarshipRepository;
	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final ScholarshipDocumentRepository scholarshipDocumentRepository;
	private final ImageStorageService imageStorageService;

	public CauNoticeCollector(RawScholarshipRepository rawScholarshipRepository,
			ScholarshipRepository scholarshipRepository,
			ScholarshipConditionRepository scholarshipConditionRepository,
			ScholarshipDocumentRepository scholarshipDocumentRepository,
			ImageStorageService imageStorageService,
			ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
		// AJAX 전용 엔드포인트라 XMLHttpRequest 헤더가 없으면 서버가 다르게 응답할 수 있다.
		this.restClient = RestClient.builder()
				.baseUrl(BASE_URL)
				.defaultHeader("User-Agent", "Mozilla/5.0 (WishConnect scholarship collector)")
				.defaultHeader("X-Requested-With", "XMLHttpRequest")
				.build();
		this.rawScholarshipRepository = rawScholarshipRepository;
		this.scholarshipRepository = scholarshipRepository;
		this.scholarshipConditionRepository = scholarshipConditionRepository;
		this.scholarshipDocumentRepository = scholarshipDocumentRepository;
		this.imageStorageService = imageStorageService;
	}

	/**
	 * 중앙대 장학공지를 수집한다.
	 *
	 * @param pages 조회할 목록 페이지 수 (1 이상)
	 */
	@Transactional
	public CollectResultResponse collect(int pages) {
		int fetched = 0;
		int saved = 0;
		int skipped = 0;

		for (Article article : fetchArticles(Math.max(pages, 1))) {
			fetched++;
			if (rawScholarshipRepository.existsBySourceAndSourceId(SOURCE, article.bbsSeq())) {
				continue;
			}
			try {
				if (collectArticle(article)) {
					saved++;
				} else {
					skipped++;
				}
			} catch (Exception e) {
				log.warn("[CauCollector] 공지 수집 실패 BBS_SEQ={} : {}", article.bbsSeq(), e.getMessage());
			}
		}

		log.info("[CauCollector] 수집 완료 목록={} 신규정제={} 스킵(마감/중복)={}", fetched, saved, skipped);
		return new CollectResultResponse(SOURCE, fetched, saved, skipped);
	}

	/**
	 * 목록 API 를 페이지 수만큼 호출한다.
	 *
	 * <p>분류(CATEGORY_NM2)와 작성일은 목록에만 있고 상세 응답에는 쓸 만한 형태로 없다.
	 * 상세를 다시 조회하지 않아도 되도록 목록 단계에서 함께 담아둔다.
	 */
	private List<Article> fetchArticles(int pages) {
		LinkedHashMap<String, Article> articles = new LinkedHashMap<>();
		for (int page = 1; page <= pages; page++) {
			try {
				JsonNode list = requestList(page).path("data").path("list");
				if (!list.isArray() || list.isEmpty()) {
					break;
				}
				for (JsonNode item : list) {
					String bbsSeq = item.path("BBS_SEQ").asText(null);
					if (bbsSeq == null || bbsSeq.isBlank()) {
						continue;
					}
					articles.putIfAbsent(bbsSeq, new Article(
							bbsSeq,
							item.path("SUBJECT").asText("").trim(),
							item.path("CATEGORY_NM2").asText(""),
							item.path("WRITE_DATE").asText("")));
				}
			} catch (Exception e) {
				log.warn("[CauCollector] 목록 조회 실패 page={} : {}", page, e.getMessage());
				break;
			}
		}
		return List.copyOf(articles.values());
	}

	private JsonNode requestList(int page) {
		MultiValueMap<String, String> form = baseForm();
		form.add("pageNo", String.valueOf(page));
		form.add("pagePerCnt", String.valueOf(PAGE_SIZE));
		form.add("S_CATE_SEQ", "");
		form.add("CATE_SEQ", "");
		form.add("TAB_NO", "");
		form.add("SEARCH_FLD", "SUBJECT");
		form.add("SEARCH", "");
		return post(LIST_PATH, form);
	}

	private JsonNode requestDetail(String bbsSeq) {
		MultiValueMap<String, String> form = baseForm();
		form.add("BBS_SEQ", bbsSeq);
		return post(DETAIL_PATH, form);
	}

	private MultiValueMap<String, String> baseForm() {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("MENU_ID", MENU_ID);
		form.add("SITE_NO", SITE_NO);
		form.add("BOARD_SEQ", BOARD_SEQ);
		form.add("BOARD_TYPE", BOARD_TYPE);
		form.add("BOARD_CATEGORY_NO", SCHOLARSHIP_CATEGORY_NO);
		form.add("P_TAB_NO", SCHOLARSHIP_TAB_NO);
		return form;
	}

	/**
	 * AJAX 엔드포인트 호출.
	 *
	 * <p>중앙대 서버는 JSON 본문을 내려주면서 Content-Type 은 text/html 로 준다. 그대로
	 * {@code body(JsonNode.class)} 를 쓰면 메시지 컨버터가 붙지 않아 응답을 못 읽으므로,
	 * 문자열로 받아 직접 파싱한다.
	 */
	private JsonNode post(String path, MultiValueMap<String, String> form) {
		String body = restClient.post()
				.uri(path)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(String.class);
		if (body == null || body.isBlank()) {
			return MissingNode.getInstance();
		}
		try {
			return objectMapper.readTree(body);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("중앙대 응답을 JSON 으로 읽지 못했습니다. path=" + path, e);
		}
	}

	/** @return true = 정제 저장됨, false = 마감 등으로 SKIPPED */
	private boolean collectArticle(Article article) {
		JsonNode data = requestDetail(article.bbsSeq()).path("data");
		if (data.isMissingNode() || data.isNull()) {
			log.warn("[CauCollector] 상세 응답이 비어 있습니다. BBS_SEQ={}", article.bbsSeq());
			return false;
		}

		String title = firstNonBlank(data.path("SUBJECT").asText(""), article.title());
		String contentHtml = data.path("CONTENTS").asText("");
		Document contentDoc = Jsoup.parse(contentHtml, BASE_URL);
		String bodyText = contentDoc.text();

		String fullText = title + "\n" + bodyText;
		// 마감 판정을 여기서 하지 않는다. 정규식이 연도를 못 읽어 올해로 가정하는 바람에
		// 모집 중인 공고를 마감으로 버렸다 — 한 배치에서 26건이 그렇게 되살아났다.
		// 수집기는 raw_html 만 남기고, 기간 판단은 근거를 대조하는 LLM 파싱이 맡는다.
		String detailUrl = detailUrl(article.bbsSeq());

		RawScholarship raw = RawScholarship.builder()
				.source(SOURCE)
				.sourceId(article.bbsSeq())
				.sourceUrl(detailUrl)
				.rawHtml(contentHtml)
				.parseStatus(ParseStatus.PENDING)
				.build();

		// 여기서 scholarship 을 만들지 않는다. 원본만 PENDING 으로 남기고 정제는 LLM 파싱이 맡는다.
		// 정규식으로 제목·기간·조건을 뽑던 코드가 LLM 과 같은 일을 두 번 하고 있었고, 품질도 나빴다.
		rawScholarshipRepository.save(raw);
		return true;
	}

	/** 사용자가 브라우저에서 여는 상세 주소. AJAX 주소가 아니라 이 값을 정제 데이터에 남긴다. */
	static String detailUrl(String bbsSeq) {
		return BASE_URL + "/cms/FR_CON/BoardView.do"
				+ "?MENU_ID=" + MENU_ID
				+ "&SITE_NO=" + SITE_NO
				+ "&BOARD_SEQ=" + BOARD_SEQ
				+ "&BOARD_CATEGORY_NO=" + SCHOLARSHIP_CATEGORY_NO
				+ "&BBS_SEQ=" + bbsSeq;
	}

	/**
	 * 본문에 연도가 없는 표기("8. 1. ~ 8. 31.")를 보정할 기준 연도.
	 * 목록의 WRITE_DATE("2026.08.12")에서 뽑고, 못 뽑으면 올해로 둔다.
	 */
	static int defaultYear(String writeDate) {
		if (writeDate != null) {
			Matcher matcher = WRITE_DATE.matcher(writeDate);
			if (matcher.find()) {
				return Integer.parseInt(matcher.group(1));
			}
		}
		return LocalDate.now().getYear();
	}

	/**
	 * 본문에서 신청기간을 추정한다.
	 *
	 * <p>본문 전체에서 첫 날짜 범위를 집는 방식은 중앙대에서 오탐이 잦다. 제출서류의 유효기간,
	 * 근로기간, 심사일정이 신청기간보다 앞에 나오는 공지가 많기 때문이다. 종료일을 잘못 집으면
	 * 이미 지난 날짜로 계산되어 <b>모집 중인 공고가 통째로 SKIPPED 되는</b> 실질적 피해가 생긴다.
	 * 그래서 신청기간 라벨 뒤쪽만 훑고, 못 찾으면 기간 없이 저장한다(마감일 없이 노출되는 편이 안전).
	 *
	 * <p>라벨은 두 단계다. 확실한 라벨("신청기간")을 먼저 넓게 보고, 없으면 표에서 한 단어로만
	 * 붙는 약한 라벨("신청")을 좁은 범위에서 본다.
	 */
	static Period parsePeriod(String text, int defaultYear) {
		Period fromStrongLabel = scanNearLabel(
				text, STRONG_PERIOD_LABEL, STRONG_LABEL_WINDOW, defaultYear);
		if (fromStrongLabel != null) {
			return fromStrongLabel;
		}
		return scanNearLabel(text, WEAK_PERIOD_LABEL, WEAK_LABEL_WINDOW, defaultYear);
	}

	/**
	 * 라벨이 나온 지점마다 그 뒤 {@code window} 글자 안에서 기간 표기를 찾는다.
	 *
	 * <p>후보가 날짜로 성립하지 않으면(예: 두 자리 연도 표기 '26.3.6. 을 26월로 읽는 경우)
	 * 버리고 다음 라벨로 넘어간다. 덕분에 "사전신청 기간(’26.3.6.~)" 뒤에 진짜 "신청기간"이
	 * 따로 있는 공지에서도 올바른 값을 집는다.
	 */
	private static Period scanNearLabel(String text, Pattern label, int window, int defaultYear) {
		Matcher labelMatcher = label.matcher(text);
		while (labelMatcher.find()) {
			String scope = text.substring(labelMatcher.end(),
					Math.min(text.length(), labelMatcher.end() + window));
			Period range = parseRange(scope, defaultYear);
			if (range != null) {
				return range;
			}
			Period deadline = parseDeadline(scope, defaultYear);
			if (deadline != null) {
				return deadline;
			}
		}
		return null;
	}

	private static Period parseRange(String scope, int defaultYear) {
		Matcher range = PERIOD_RANGE.matcher(scope);
		while (range.find()) {
			try {
				int startYear = range.group(1) != null ? Integer.parseInt(range.group(1)) : defaultYear;
				LocalDate start = LocalDate.of(startYear,
						Integer.parseInt(range.group(2)), Integer.parseInt(range.group(3)));
				int endYear = range.group(4) != null ? Integer.parseInt(range.group(4)) : startYear;
				LocalDate end = LocalDate.of(endYear,
						Integer.parseInt(range.group(5)), Integer.parseInt(range.group(6)));
				if (end.isBefore(start)) {
					// 연도 없는 "12.20 ~ 1.10" 같은 해 넘김 표기
					end = end.plusYears(1);
				}
				return new Period(start.atStartOfDay(), end.atTime(parseLastTimeOrEndOfDay(range.group())));
			} catch (Exception e) {
				// 2월 30일처럼 성립하지 않는 날짜. 다음 후보를 본다.
			}
		}
		return null;
	}

	private static Period parseDeadline(String scope, int defaultYear) {
		Matcher deadline = PERIOD_DEADLINE.matcher(scope);
		while (deadline.find()) {
			try {
				int endYear = deadline.group(1) != null
						? Integer.parseInt(deadline.group(1)) : defaultYear;
				LocalDate end = LocalDate.of(endYear,
						Integer.parseInt(deadline.group(2)), Integer.parseInt(deadline.group(3)));
				return new Period(null, end.atTime(parseDeadlineTime(deadline.group())));
			} catch (Exception e) {
				// 다음 후보를 본다.
			}
		}
		return null;
	}

	private static LocalTime parseDeadlineTime(String matchedText) {
		Matcher time = TIME_IN_TEXT.matcher(matchedText);
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
		Matcher time = TIME_IN_TEXT.matcher(matchedText);
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





	private static String firstNonBlank(String first, String fallback) {
		return first != null && !first.isBlank() ? first.trim() : fallback;
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

	/** 목록에서 얻는 게시글 메타. 분류·작성일은 상세 응답에 없어 여기서 들고 간다. */
	record Article(String bbsSeq, String title, String category, String writeDate) {
	}

	record Period(LocalDateTime start, LocalDateTime end) {
	}
}
