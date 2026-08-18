package com.wishconnect.domain.scholarship.util;

import java.util.List;
import java.util.Optional;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * 공지 상세 HTML 에서 <b>제목과 본문만</b> 골라낸다.
 *
 * <p>기존에는 본문 영역을 못 찾으면 <b>페이지 전체 텍스트로 폴백</b>했다. 그 결과 LLM 이 읽은 게
 * 공지 내용이 아니라 사이트 메뉴였다 — "국민대학교 대학소개 Make the Rule, Break the Rule
 * 교육이념ㆍ비전 건학이념…". 전수조사에서 서울시립대 27건, 세종대 12건, 국민대 8건, 동국대 1건이
 * 이 상태였다.
 *
 * <p>폴백을 없앴다. <b>본문 영역을 못 찾으면 못 찾았다고 한다.</b> 첨부파일·이미지만 있는 공지가
 * 실제로 있고(국민대 4043 은 본문 영역이 빈 껍데기였다), 그런 공지는 메뉴를 먹여 억지로 파싱하는
 * 것보다 건너뛰는 편이 낫다. 크레딧도 아끼고, 무엇보다 근거 없는 값이 DB 에 남지 않는다.
 *
 * <p>후보를 <b>순서대로 시도해 처음으로 내용이 있는 것</b>을 쓴다. 이 순서가 중요하다. 서울시립대는
 * {@code .hwp_editor_board_content} 와 {@code .notice_tb_view_contents} 가 둘 다 있는데 앞의 것이
 * 비어 있다. 예전 코드는 콤마로 이어 붙여 한 번에 찾았기 때문에 빈 쪽을 집고 폴백해 버렸다.
 */
public final class NoticeHtmlExtractor {

	/** 본문으로 인정할 최소 길이. 이보다 짧으면 제목만 있는 껍데기로 본다. */
	private static final int MIN_BODY_CHARS = 40;

	/** 이미지 설명으로 인정할 최소 길이. "포스터", "이미지1" 같은 건 내용이 아니다. */
	private static final int MIN_ALT_CHARS = 20;

	private static final java.util.regex.Pattern IMAGE_EXT =
			java.util.regex.Pattern.compile("(?i)\\.(jpe?g|png|gif|webp)(\\?.*)?$");
	/** 로고·아이콘 같은 장식 이미지는 포스터가 아니다. */
	private static final java.util.regex.Pattern NON_POSTER = java.util.regex.Pattern.compile(
			"(?i)logo|icon|btn|banner|common|header|footer|blank|bullet|og_thumbnail|ssu_ogimage|favicon|sns|share|/resources/images/");

	/**
	 * 본문 영역 후보. 실제 수집 대상 게시판을 열어 확인한 순서다.
	 *
	 * <p>앞쪽일수록 "본문만" 정확히 담는 것이고, 뒤로 갈수록 제목·작성일 같은 머리말이 섞인다.
	 */
	private static final List<String> BODY_SELECTORS = List.of(
			".notice_tb_view_contents",   // 서울시립대
			".fr-view",                   // 세종대 · 홍익대
			".view_cont",                 // 국민대 · 동국대
			".hwp_editor_board_content",  // 한글 에디터 공통
			".artclView",                 // 전북·인천 등 표준 스킨
			".board-view",                // 연세대 · 한림대
			".board02 .row.contents",      // 경희대
			".row.contents",
			".entry-content",             // 워드프레스 계열
			".board_view",
			".bbs_view",
			".view-con",
			".view_content",
			".b-content",
			".board_cont",
			".col-12.col-lg-9");          // 숭실대(부트스트랩 그리드) — 가장 마지막에 둔다

	/** 제목 영역 후보. 홍익대는 이게 없어 페이지 문서 제목("공유팝업 열기…")을 쓰고 있었다. */
	private static final List<String> TITLE_SELECTORS = List.of(
			".b-title",                   // 세종대 · 홍익대
			"td.fontBold",                // 서울시립대
			".view_tit",                  // 국민대
			".artclViewTitle",
			".board-view .title",         // 연세대 · 한림대
			".board_view .tit",
			".view-title",
			".bbs-view-title",
			".view_top .tit",
			".subject");

	/** 본문 자리에 이게 섞여 있으면 메뉴를 집은 것이다. */
	private static final List<String> CHROME_MARKERS = List.of(
			"바로가기", "퀵메뉴", "주메뉴", "전체메뉴", "공유팝업", "챗봇", "LOGIN");

	private NoticeHtmlExtractor() {
	}

	/**
	 * 본문 텍스트. 못 찾으면 {@code empty} — <b>페이지 전체로 폴백하지 않는다.</b>
	 *
	 * @param preferred 사이트별로 지정된 셀렉터(있으면 가장 먼저 시도한다)
	 */
	/**
	 * 본문이 <b>이미지뿐이라 {@code alt} 로 대체했는가.</b>
	 *
	 * <p>포스터 한 장만 올린 공고를 alt 덕분에 살려도, 거기 담긴 건 이미지 설명 한 줄뿐이라
	 * 조건·제출서류는 여전히 비어 있다. 나중에 OCR 을 붙일 때 <b>이 건들이 대상</b>인데,
	 * 상태는 PARSED 라 {@code IMAGE_ONLY} 로 골라낼 수 없다. 그래서 따로 표시해 둔다.
	 */
	public static boolean bodyFromImageAlt(Document doc, String preferred) {
		if (doc == null) {
			return false;
		}
		for (String selector : candidates(preferred)) {
			Element element;
			try {
				element = doc.selectFirst(selector);
			} catch (RuntimeException e) {
				continue;
			}
			if (element == null) {
				continue;
			}
			if (normalize(element.text()).length() >= MIN_BODY_CHARS) {
				return false;   // 글자만으로 충분했다
			}
			if (!imageDescriptions(element).isBlank()) {
				return true;    // 글자가 모자라 alt 로 채웠다
			}
		}
		return false;
	}

	public static Optional<String> body(Document doc, String preferred) {
		if (doc == null) {
			return Optional.empty();
		}
		for (String selector : candidates(preferred)) {
			Optional<String> found = textOf(doc, selector);
			if (found.isPresent()) {
				return found;
			}
		}
		return Optional.empty();
	}

	/**
	 * 본문 자리에 <b>이미지만</b> 있는가.
	 *
	 * <p>본문 영역은 찾았는데 읽을 글자가 없고 그림만 있는 경우다. 공고 내용이 포스터 안에 다 들어
	 * 있으므로 "내용 없음" 과는 다르게 다뤄야 한다 — 나중에 OCR 이나 이미지를 읽는 모델로 살릴 수 있다.
	 * 그때 대상을 골라내려면 지금 구분해 둬야 한다.
	 */
	public static boolean imageOnly(Document doc, String preferred) {
		if (doc == null || body(doc, preferred).isPresent()) {
			return false;
		}
		for (String selector : candidates(preferred)) {
			Element element;
			try {
				element = doc.selectFirst(selector);
			} catch (RuntimeException e) {
				continue;
			}
			if (element != null && !element.select("img").isEmpty()) {
				return true;
			}
		}
		return false;
	}

	public static Optional<String> title(Document doc, String preferred) {
		if (doc == null) {
			return Optional.empty();
		}
		for (String selector : titleCandidates(preferred)) {
			Element element = doc.selectFirst(selector);
			if (element != null) {
				String text = stripMeta(normalize(
						element.ownText().isBlank() ? element.text() : element.ownText()));
				if (!text.isBlank()) {
					return Optional.of(text);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * 메뉴·네비게이션이 섞였는가.
	 *
	 * <p>셀렉터를 아무리 맞춰도 다음 게시판에서 또 빗나간다. 그때 조용히 쓰레기가 쌓이는 대신
	 * 드러나게 하려는 안전망이다 — 이번에도 신호가 없어서 5개 학교만 보고 국민대·홍익대를 놓쳤다.
	 */
	/** 첨부로 볼 확장자. 게시판이 링크 텍스트에 파일명을 그대로 쓴다. */
	private static final java.util.regex.Pattern ATTACHMENT_NAME = java.util.regex.Pattern.compile(
			"(?i)\\.(hwpx?|pdf|docx?|xlsx?|pptx?|zip|jpe?g|png)\\b");

	/**
	 * 첨부파일 이름들. 본문에 없는 정보가 파일명에 있다.
	 *
	 * <p>본문을 첨부에만 싣는 게시판이 있다(한국외대·숭실대). 그런 공고는 본문이 비어 있어
	 * 아무것도 못 뽑는데, 파일명만은 게시판에 노출된다. 실제로 자소서 필요 여부가 여기서
	 * 세 건 갈렸다 — {@code "…사랑나눔 장학생 자기소개서.docx"}.
	 *
	 * <p>목록을 12개로 끊는다. 공고문·서식·안내가 보통 3~5개이고, 그보다 많으면 게시판
	 * 사이드바의 다른 글 링크가 섞인 것이다.
	 */
	public static List<String> attachmentNames(Document doc) {
		java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
		for (Element link : doc.select("a[href]")) {
			String text = normalize(link.text());
			if (text.isBlank() || text.length() > 150) {
				continue;
			}
			if (ATTACHMENT_NAME.matcher(text).find()) {
				names.add(text);
			}
		}
		return names.stream().limit(12).toList();
	}

	public static boolean looksLikeChrome(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		String head = text.length() > 300 ? text.substring(0, 300) : text;
		return CHROME_MARKERS.stream().filter(head::contains).count() >= 2;
	}

	private static List<String> candidates(String preferred) {
		if (preferred == null || preferred.isBlank()) {
			return BODY_SELECTORS;
		}
		// 콤마로 이어 붙여 오면 한 번에 찾지 않고 하나씩 시도한다(앞의 것이 비어 있을 수 있다).
		List<String> split = List.of(preferred.split("\\s*,\\s*"));
		return java.util.stream.Stream.concat(split.stream(), BODY_SELECTORS.stream()).distinct().toList();
	}

	private static List<String> titleCandidates(String preferred) {
		if (preferred == null || preferred.isBlank()) {
			return TITLE_SELECTORS;
		}
		List<String> split = List.of(preferred.split("\\s*,\\s*"));
		return java.util.stream.Stream.concat(split.stream(), TITLE_SELECTORS.stream()).distinct().toList();
	}

	/**
	 * 공지에 실린 포스터 이미지 URL. 없으면 null.
	 *
	 * <p>수집기에 있던 것을 옮겨 왔다. 수집기가 더는 {@code scholarship} 을 만들지 않아
	 * 포스터를 붙일 시점이 LLM 파싱으로 넘어갔고, 양쪽에서 써야 하는 코드가 됐다.
	 */
	public static String posterUrl(Document doc) {
		Element ogImg = doc.selectFirst("meta[property=og:image][content]");
		if (ogImg != null) {
			String src = ogImg.attr("content").trim();
			if (IMAGE_EXT.matcher(src).find() && !NON_POSTER.matcher(src).find()) {
				return src;
			}
		}
		for (Element img : doc.select(
				".board_view .view_cont img[src], .artclView img[src], .view-con img[src], "
						+ ".view_cont img[src], .article-view img[src], .content img[src], .contents img[src], "
						+ ".bg-white img[src], .entry-content img[src], article img[src], main img[src]")) {
			String src = img.attr("abs:src");
			if (!src.isBlank() && IMAGE_EXT.matcher(src).find() && !NON_POSTER.matcher(src).find()) {
				return src;
			}
		}
		for (Element link : doc.select(
				".board_view .view_cont a[href*=download], .artclView a[href*=download], .view-con a[href*=download], "
						+ ".view_cont a[href*=download], .article-view a[href*=download], .content a[href*=download], "
						+ ".contents a[href*=download], .bg-white a[href*=download], .entry-content a[href*=download], "
						+ "article a[href*=download], main a[href*=download]")) {
			String name = link.text();
			if (IMAGE_EXT.matcher(name.strip()).find()) {
				return link.attr("abs:href");
			}
		}
		return null;
	}

	private static Optional<String> textOf(Document doc, String selector) {
		Element element;
		try {
			element = doc.selectFirst(selector);
		} catch (RuntimeException e) {
			return Optional.empty();   // 잘못된 셀렉터가 설정돼 있어도 수집을 멈추지 않는다
		}
		if (element == null) {
			return Optional.empty();
		}
		String text = normalize(element.text());
		if (text.length() < MIN_BODY_CHARS) {
			text = normalize(text + " " + imageDescriptions(element));
		}
		return text.length() >= MIN_BODY_CHARS ? Optional.of(text) : Optional.empty();
	}

	/**
	 * 본문이 포스터 이미지 한 장뿐일 때, 이미지에 달린 설명을 본문으로 쓴다.
	 *
	 * <p>공고를 이미지로만 올리는 게시판이 많다. 그런 공지는 읽을 글자가 없어 건너뛸 수밖에 없는데,
	 * 접근성 때문에 {@code alt} 에 내용을 성실히 적어 두는 곳이 있다. 한국외대가 그렇다 —
	 * "2026년 7월 1일 수요일 오전 9시부터 11월 17일 화요일 오후 6시까지 신청 가능" 처럼
	 * <b>모집기간까지</b> 들어 있다. 공짜로 건질 수 있는 것을 버릴 이유가 없다.
	 *
	 * <p>{@code alt} 가 파일명이거나 "이미지"·"포스터" 같은 한 단어면 내용이 아니므로 버린다.
	 */
	private static String imageDescriptions(Element element) {
		StringBuilder joined = new StringBuilder();
		for (Element image : element.select("img[alt]")) {
			String alt = normalize(image.attr("alt"));
			if (alt.length() >= MIN_ALT_CHARS && !alt.matches(".*\\.(png|jpe?g|gif|webp|bmp)$")) {
				joined.append(alt).append(' ');
			}
		}
		return joined.toString().trim();
	}

	/**
	 * 제목 뒤에 붙은 게시판 메타를 잘라낸다.
	 *
	 * <p>제목 영역에 작성일·조회수를 같이 넣는 스킨이 있다(동국대·한림대).
	 * {@code "월드머시코리아장학생 선발 안내 등록일 2026.08.13. 조회 126"} 처럼 들어온다.
	 */
	private static String stripMeta(String title) {
		int cut = -1;
		for (String marker : List.of("등록일", "작성일", "조회수", "조회 ", "작성자", "담당부서")) {
			int at = title.indexOf(marker);
			if (at > 0 && (cut < 0 || at < cut)) {
				cut = at;
			}
		}
		return cut > 0 ? title.substring(0, cut).trim() : title;
	}

	private static String normalize(String text) {
		return text == null ? "" : text.replaceAll("\\s+", " ").trim();
	}
}
