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
import java.util.Set;
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
경희대학교 장학공지 수집기입니다.

공통 CMS(artclView 계열)를 쓰는 UnivNoticeCollector 로는 대응할 수 없어 별도 수집기로 둡니다.
- 목록 링크가 href 가 아니라 javascript:view('{boardId}','') 형태라 정규식으로 boardId 를 뽑아야 합니다.
- 장학/근로 게시판이 menuNo 로만 갈리는 같은 게시판(BMSR00040)이라 두 menuNo 를 함께 순회합니다.
- 페이지네이션이 GET 파라미터가 아니라 form POST(fnSubmitForm) 라서 pageIndex 를 폼 데이터로 보냅니다.

신청기간 추출이 경희대의 최대 난점입니다. 공고 본문에 '근무기간 : 2026.09.01 ~ 2027.02.28' 같은
근로 수행기간이 거의 항상 들어 있어, 본문에서 첫 날짜 범위를 집으면 그걸 신청기간으로 오인합니다.
종료일을 잘못 집으면 마감으로 계산되어 모집 중인 공고가 통째로 버려지므로, 다음 순서로만 인정합니다.
  1) 제목의 마감 표기 — 경희대는 "(~8/21까지)" 를 제목에 붙이는 관례가 뚜렷해 신뢰도가 가장 높다.
  2) 본문의 신청기간 라벨 뒤 — 경희대는 '지원일정' 이라는 표기를 자주 쓴다.
'모집' 같은 약한 라벨은 '모집인원' 에 걸려 근무기간을 끌어오므로 쓰지 않습니다.

raw_scholarship(source=UNIV_KHU)에 원본 보존 후 scholarship 으로 정제하는 흐름은
다른 대학 수집기와 동일하게 맞췄습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KhuNoticeCollector {

	static final String SOURCE = "UNIV_KHU";
	private static final String PROVIDER = "경희대학교";
	private static final String BASE_URL = "https://www.khu.ac.kr";
	private static final String LIST_URL = BASE_URL + "/kor/user/bbs/BMSR00040/list.do";
	private static final String VIEW_URL = BASE_URL + "/kor/user/bbs/BMSR00040/view.do";

	/** 장학(200318) + 근로(200361). 같은 게시판이지만 menuNo 로 분리되어 있다. */
	private static final List<String> MENU_NOS = List.of("200318", "200361");

	/** 목록의 게시글 링크: javascript:view('322635',''); → 그룹1 = boardId */
	private static final Pattern ARTICLE_LINK = Pattern.compile("view\\('(\\d+)'");

	/**
	 * 날짜 뒤에 붙는 요일 표기. 경희대는 "(금)" 처럼 괄호로 감싸기도 하고 "9.9수까지" 처럼
	 * 괄호 없이 한 글자만 붙이기도 해서 양쪽을 모두 허용한다.
	 */
	private static final String WEEKDAY = "(?:\\([^)]*\\)|[월화수목금토일])?";
	private static final String TIME_SUFFIX = "(?:\\d{1,2}\\s*(?:시|:)\\s*\\d{0,2})?";

	/**
	 * 날짜 범위. 경희대는 "8/21", "2026.09.01", "8. 21." 표기가 섞여 나와 구분자에 / 와 - 를 모두 넣는다.
	 */
	private static final Pattern PERIOD_RANGE = Pattern.compile(
			"(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ WEEKDAY + "[^~]{0,30}~\\s*"
					+ "(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ WEEKDAY + "\\s*" + TIME_SUFFIX);

	/**
	 * "~ 9.4.(금)" 처럼 물결로 시작하는 마감 표기. 경희대는 제목에서 "까지" 를 자주 생략하므로
	 * 물결이 앞에 있으면 종결어가 없어도 마감으로 인정한다.
	 */
	private static final Pattern DEADLINE_AFTER_TILDE = Pattern.compile(
			"~\\s*(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ WEEKDAY + "\\s*" + TIME_SUFFIX + "\\s*(?:까지|마감|기한)?");
	/**
	 * "2026.08.13(목) 까지" 처럼 물결 없이 종결어로만 끝나는 마감 표기.
	 * 물결이 없으면 아무 날짜나 마감으로 잡히므로 종결어를 반드시 요구한다.
	 */
	private static final Pattern DEADLINE_WITH_SUFFIX = Pattern.compile(
			"(?:(20\\d{2})\\s*(?:[.년/-])\\s*)?(\\d{1,2})\\s*(?:[.월/-])\\s*(\\d{1,2})\\s*(?:일)?\\s*\\.?\\s*"
					+ WEEKDAY + "\\s*" + TIME_SUFFIX + "\\s*(?:까지|마감|기한)");
	private static final Pattern TIME_IN_TEXT = Pattern.compile("(\\d{1,2})\\s*(?:시|:)\\s*(\\d{0,2})");

	/**
	 * 신청기간으로 인정할 최대 일수.
	 * 표가 텍스트로 뭉개지면 무관한 두 날짜가 짝지어져 1년에 가까운 범위가 나온다
	 * (실측: 근무일 안내 표에서 2026-07-13 ~ 2027-06-30). 신청 접수를 300일 넘게 여는 공고는
	 * 사실상 없으므로, 이보다 길면 잘못 짝지어진 것으로 보고 버린다.
	 */
	private static final int MAX_PERIOD_DAYS = 300;

	/**
	 * 신청기간을 가리키는 것이 분명한 라벨.
	 * '지원일정' 은 경희대 근로/인턴장학 공고가 신청기간에 쓰는 고유 표기라 반드시 포함해야 한다.
	 * 반대로 '모집' 만 있는 약한 라벨은 '모집인원' 에 걸려 근무기간을 끌어오므로 넣지 않는다.
	 */
	private static final Pattern PERIOD_LABEL = Pattern.compile(
			"(신청\\s*[·ㆍ,/]?\\s*접수\\s*기간|신청\\s*기간|접수\\s*기간|모집\\s*기간|지원\\s*기간|지원\\s*일정"
					+ "|신청\\s*일정|접수\\s*일정|공모\\s*기간|신청\\s*기한|접수\\s*기한|제출\\s*기한|제출\\s*기간"
					+ "|신청\\s*및\\s*접수|신청\\s*방법\\s*및\\s*기간)");
	private static final int LABEL_WINDOW = 160;

	/** 게시글 상세의 등록일. 연도 없는 표기를 보정할 기준 연도로 쓴다. */
	private static final Pattern POSTED_DATE = Pattern.compile("(20\\d{2})-(\\d{1,2})-(\\d{1,2})");

	private static final Pattern DOCUMENT_SECTION = Pattern.compile(
			"(?:제출\\s*서류|구비\\s*서류|제출서류|신청\\s*방법\\s*및\\s*제출서류)\\s*[:：]?\\s*(.{0,600})");
	private static final Pattern SECTION_BOUNDARY = Pattern.compile(
			"(?:\\d{1,2}\\s*[.)]\\s*)?(?:신청\\s*기한|신청\\s*기간|지원\\s*일정|장학\\s*금액|문의|문의처|유의\\s*사항|합격자|선발|지원\\s*자격)");
	private static final Pattern ESSAY_DOCUMENT = Pattern.compile(
			"(자기\\s*소개서|자소서|학업\\s*계획서|전인적\\s*성장\\s*계획서|에세이|essay)", Pattern.CASE_INSENSITIVE);
	private static final int MAX_DOCUMENTS = 12;

	private static final Pattern IMAGE_EXT = Pattern.compile("(?i)\\.(jpe?g|png|gif|webp)(\\?.*)?$");
	private static final Pattern NON_POSTER =
			Pattern.compile("(?i)logo|icon|btn|banner|common|header|footer|blank|bullet|deco");

	/**
	 * 근로 대가로 지급되는 근로장학금.
	 * '경희인턴' 은 경희대 교내 근로장학 프로그램의 고유 명칭이라, 뒤에 붙는 표기가
	 * '인턴장학' / '(교내장학)' 로 갈려도 같은 유형으로 묶이도록 프로그램명 자체를 넣는다.
	 */
	private static final Pattern WORK_STUDY_KEYWORD =
			Pattern.compile("(국가근로|교내근로|일반근로|근로장학|인턴장학|경희인턴)");
	/** 외부 재단·기관 장학의 학교 경유 공지. 경희대는 제목에 [교외] 태그 대신 재단명이 바로 오는 경우가 많다. */
	private static final Pattern EXTERNAL_TAG = Pattern.compile("\\[(교외|학교추천|국가|국가근로|정부초청)]");
	private static final Pattern PROVIDER_IN_TITLE = Pattern.compile(
			"([가-힣A-Za-z0-9·()]+(?:장학재단|장학회|문화재단|복지재단|공익재단|인재육성재단|진흥원|동문회|위원회))");

	/** 목록 제목 앞에 붙는 캠퍼스/구분 라벨(공통, 서울, 국제 등). 정제 시 제거 대상. */
	private static final Pattern CAMPUS_LABEL = Pattern.compile("^(공통|서울|국제)\\s+");

	private static final int TIMEOUT_MS = 10_000;
	private static final String USER_AGENT = "Mozilla/5.0 (WishConnect scholarship collector)";

	private final RawScholarshipRepository rawScholarshipRepository;
	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final ScholarshipDocumentRepository scholarshipDocumentRepository;
	private final ImageStorageService imageStorageService;

	/**
	 * 경희대 장학·근로 게시판을 수집한다.
	 *
	 * @param pages menuNo 별로 조회할 목록 페이지 수 (1 이상)
	 */
	@Transactional
	public CollectResultResponse collect(int pages) {
		int fetched = 0;
		int saved = 0;
		int skipped = 0;

		for (String menuNo : MENU_NOS) {
			for (String boardId : fetchBoardIds(menuNo, Math.max(pages, 1))) {
				fetched++;
				if (rawScholarshipRepository.existsBySourceAndSourceId(SOURCE, boardId)) {
					continue;
				}
				try {
					if (collectArticle(menuNo, boardId)) {
						saved++;
					} else {
						skipped++;
					}
				} catch (Exception e) {
					log.warn("[KhuCollector] 공지 수집 실패 menuNo={} boardId={} : {}",
							menuNo, boardId, e.getMessage());
				}
			}
		}

		log.info("[KhuCollector] 수집 완료 목록={} 신규정제={} 스킵(마감/중복)={}", fetched, saved, skipped);
		return new CollectResultResponse(SOURCE, fetched, saved, skipped);
	}

	/**
	 * 목록 페이지에서 boardId 를 뽑는다.
	 * 페이지네이션이 form POST 라서 1페이지는 GET, 2페이지부터는 pageIndex 를 폼 데이터로 보낸다.
	 */
	private List<String> fetchBoardIds(String menuNo, int pages) {
		Set<String> boardIds = new LinkedHashSet<>();
		for (int page = 1; page <= pages; page++) {
			try {
				Document doc = requestListPage(menuNo, page);
				List<String> pageIds = extractBoardIds(doc);
				if (pageIds.isEmpty()) {
					// 마지막 페이지를 넘어선 경우. 더 요청해도 의미가 없다.
					break;
				}
				boardIds.addAll(pageIds);
			} catch (Exception e) {
				log.warn("[KhuCollector] 목록 조회 실패 menuNo={} page={} : {}", menuNo, page, e.getMessage());
				break;
			}
		}
		return List.copyOf(boardIds);
	}

	private Document requestListPage(String menuNo, int page) throws Exception {
		var connection = Jsoup.connect(LIST_URL)
				.data("menuNo", menuNo)
				.userAgent(USER_AGENT)
				.timeout(TIMEOUT_MS);
		if (page <= 1) {
			return connection.get();
		}
		return connection.data("pageIndex", String.valueOf(page)).post();
	}

	/** 목록 문서에서 javascript:view('...') 링크의 boardId 를 순서대로 수집한다. */
	static List<String> extractBoardIds(Document doc) {
		List<String> ids = new ArrayList<>();
		for (Element anchor : doc.select("a[href*=view(]")) {
			Matcher matcher = ARTICLE_LINK.matcher(anchor.attr("href"));
			if (matcher.find() && !ids.contains(matcher.group(1))) {
				ids.add(matcher.group(1));
			}
		}
		return ids;
	}

	/** @return true = 정제 저장됨, false = 마감 등으로 SKIPPED */
	private boolean collectArticle(String menuNo, String boardId) throws Exception {
		String detailUrl = detailUrl(menuNo, boardId);
		Document doc = Jsoup.connect(detailUrl).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
		String title = extractTitle(doc);
		String bodyText = extractBody(doc);

		Period period = parsePeriod(title, bodyText, extractPostedYear(doc));
		boolean closed = period != null && period.end() != null
				&& period.end().isBefore(LocalDateTime.now());

		RawScholarship raw = RawScholarship.builder()
				.source(SOURCE)
				.sourceId(boardId)
				.sourceUrl(detailUrl)
				.rawHtml(doc.outerHtml())
				.parseStatus(closed ? ParseStatus.SKIPPED : ParseStatus.PENDING)
				.parseError(closed ? "모집종료일이 지난 공지입니다." : null)
				.build();

		if (closed) {
			rawScholarshipRepository.save(raw);
			return false;
		}

		String dedupKey = ScholarshipDedupKey.of(SOURCE, boardId);
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

	static String detailUrl(String menuNo, String boardId) {
		return VIEW_URL + "?menuNo=" + menuNo + "&boardId=" + boardId;
	}

	/**
	 * 제목 추출. 경희대 상세 스킨은 제목이 .tit 에 들어가고,
	 * 그 앞에 캠퍼스 라벨(공통/서울/국제)이 자식 span 으로 붙는다.
	 */
	static String extractTitle(Document doc) {
		Element tit = doc.selectFirst(".tit");
		if (tit != null && !tit.text().isBlank()) {
			return tit.text().trim().replaceAll("\\s+", " ");
		}
		Element h3 = doc.selectFirst(".board02 h3, h3");
		if (h3 != null && !h3.text().isBlank()) {
			return h3.text().trim();
		}
		return doc.title().trim();
	}

	/**
	 * 본문 추출.
	 * <p>
	 * 경희대 상세 스킨은 .board02 안에 제목/작성자/본문/첨부를 각각 .row 로 나눠 담고,
	 * 실제 공고 내용은 .row.contents 에만 들어간다. 첨부파일(hwp)이나 포스터 이미지로만
	 * 안내하는 공지는 이 영역이 비어 있는데, 이 경우 작성자·첨부파일명이라도 남기도록
	 * .board02 전체로 폴백한다(신청기간은 제목에서도 추출되므로 정제에 지장 없음).
	 */
	static String extractBody(Document doc) {
		Element contents = doc.selectFirst(".board02 .row.contents");
		if (contents != null && !contents.text().isBlank()) {
			return contents.text();
		}
		Element board = doc.selectFirst(".board02");
		if (board != null && !board.text().isBlank()) {
			return board.text();
		}
		return doc.body() == null ? "" : doc.body().text();
	}

	/**
	 * 등록일의 연도. 연도를 생략한 표기("8/21까지")를 보정하는 기준이 된다.
	 * 상세 헤더에 "2026-08-13조회수 63" 형태로 붙어 있으며, 못 읽으면 올해로 둔다.
	 */
	static int extractPostedYear(Document doc) {
		Element board = doc.selectFirst(".board02");
		if (board != null) {
			Matcher matcher = POSTED_DATE.matcher(board.text());
			if (matcher.find()) {
				return Integer.parseInt(matcher.group(1));
			}
		}
		return LocalDate.now().getYear();
	}

	/**
	 * 신청기간을 추출한다.
	 *
	 * <p>경희대 공고는 '근무기간 : 2026.09.01 ~ 2027.02.28' 같은 근로 수행기간을 거의 항상 담고 있어,
	 * 본문에서 첫 날짜 범위를 집으면 그걸 신청기간으로 오인한다. 잘못된 종료일은 공고를 마감 처리해
	 * 통째로 버리게 하므로, 아래 순서로만 인정하고 못 찾으면 기간 없이 저장한다.
	 *
	 * <ol>
	 *   <li>제목의 마감 표기 "(~8/21까지)" — 경희대가 제목에 붙이는 관례라 신뢰도가 가장 높다.</li>
	 *   <li>본문에서 신청기간 라벨('지원일정' 포함) 뒤쪽.</li>
	 * </ol>
	 */
	static Period parsePeriod(String title, String body, int defaultYear) {
		// 제목에 "(2026.8.12.(화)~9.9.(수))" 처럼 범위가 통째로 들어가는 공고가 있다.
		// 마감을 먼저 보면 시작일을 버리게 되므로 범위를 앞에 둔다.
		Period titleRange = parseRange(title, defaultYear);
		if (titleRange != null) {
			return titleRange;
		}
		Period titleDeadline = parseDeadline(title, defaultYear);
		if (titleDeadline != null) {
			return titleDeadline;
		}
		return scanNearLabel(body, defaultYear);
	}

	/**
	 * 라벨이 나온 지점마다 그 뒤 {@value #LABEL_WINDOW} 글자 안에서 기간 표기를 찾는다.
	 * 후보가 날짜로 성립하지 않으면 버리고 다음 라벨로 넘어간다.
	 */
	private static Period scanNearLabel(String text, int defaultYear) {
		if (text == null || text.isBlank()) {
			return null;
		}
		Matcher labelMatcher = PERIOD_LABEL.matcher(text);
		while (labelMatcher.find()) {
			String scope = text.substring(labelMatcher.end(),
					Math.min(text.length(), labelMatcher.end() + LABEL_WINDOW));
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
		if (scope == null || scope.isBlank()) {
			return null;
		}
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
				if (java.time.temporal.ChronoUnit.DAYS.between(start, end) > MAX_PERIOD_DAYS) {
					// 표가 뭉개져 무관한 두 날짜가 짝지어진 경우. 다음 후보를 본다.
					continue;
				}
				return new Period(start.atStartOfDay(), end.atTime(parseLastTimeOrEndOfDay(range.group())));
			} catch (Exception e) {
				// 2월 30일처럼 성립하지 않는 날짜. 다음 후보를 본다.
			}
		}
		return null;
	}

	/** 물결 표기를 먼저 보고, 없으면 종결어가 붙은 표기를 본다. */
	private static Period parseDeadline(String scope, int defaultYear) {
		if (scope == null || scope.isBlank()) {
			return null;
		}
		Period afterTilde = matchDeadline(DEADLINE_AFTER_TILDE, scope, defaultYear);
		return afterTilde != null ? afterTilde : matchDeadline(DEADLINE_WITH_SUFFIX, scope, defaultYear);
	}

	private static Period matchDeadline(Pattern pattern, String scope, int defaultYear) {
		Matcher deadline = pattern.matcher(scope);
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

	/**
	 * 공지 본문에서 지원 자격 조건을 뽑아 저장한다.
	 * 숫자 구조화(valueInt)는 이후 조건 추출 배치가 이어서 처리하므로 여기서는 원문 문장만 남긴다.
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
		for (String token : section.split("(?:[①②③④⑤⑥⑦⑧⑨⑩]|\\d{1,2}\\s*[.)]|[,/]|\\s+-\\s+|▶|▪|◾|○|가\\.|나\\.|다\\.)")) {
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

	/** 본문 인라인 이미지에서 포스터 후보를 찾아 S3 에 저장한다(실패해도 수집은 계속). */
	private void storePoster(Document doc, Scholarship scholarship, String title) {
		String posterUrl = findPosterUrl(doc);
		if (posterUrl == null) {
			return;
		}
		imageStorageService.storeFromUrl(posterUrl, "scholarship/khu",
				ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarship.getId(), title);
	}

	/**
	 * 포스터 후보 URL. 경희대는 본문 이미지가 /upload/cross/images/ 아래에 오고
	 * 공통 UI 이미지(로고/푸터)는 /resources/user/_common/ 아래라 NON_POSTER 로 걸러진다.
	 */
	static String findPosterUrl(Document doc) {
		for (Element img : doc.select(".row.contents img[src], .board02 img[src], img[src]")) {
			String src = img.attr("abs:src");
			if (!src.isBlank() && IMAGE_EXT.matcher(src).find() && !NON_POSTER.matcher(src).find()) {
				return src;
			}
		}
		for (Element link : doc.select("a[href*=download], a[href*=fileDown]")) {
			if (IMAGE_EXT.matcher(link.text().strip()).find()) {
				return link.attr("abs:href");
			}
		}
		return null;
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
	 *   <li>근로·인턴장학은 성격이 달라 WORK_STUDY 로 우선 분리</li>
	 *   <li>[교외] 등 태그가 있거나 제목에 외부 재단명이 있으면 EXTERNAL (운영기관명 추출 시도)</li>
	 *   <li>그 외는 INTERNAL</li>
	 * </ol>
	 */
	static Classification classify(String title) {
		if (WORK_STUDY_KEYWORD.matcher(title).find()) {
			return new Classification(ScholarshipType.WORK_STUDY, PROVIDER);
		}
		Matcher providerMatcher = PROVIDER_IN_TITLE.matcher(title);
		if (providerMatcher.find()) {
			return new Classification(ScholarshipType.EXTERNAL, providerMatcher.group(1));
		}
		if (EXTERNAL_TAG.matcher(title).find()) {
			return new Classification(ScholarshipType.EXTERNAL, PROVIDER);
		}
		return new Classification(ScholarshipType.INTERNAL, PROVIDER);
	}

	/** 목록에서 딸려온 캠퍼스 라벨을 떼고 공백을 정리한다. */
	static String cleanTitle(String title) {
		String cleaned = CAMPUS_LABEL.matcher(title.replaceAll("\\s+", " ").trim()).replaceFirst("");
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
