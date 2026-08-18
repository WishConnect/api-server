package com.wishconnect.domain.scholarship.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UnivNoticeCollectorTest {

	@Test
	@DisplayName("홍익대 article.offset 페이지 파라미터는 0, 10, 20 순으로 계산한다")
	void buildsHongikOffsetListPageUrl() {
		var site = new UnivNoticeProperties.Site(
				"hongik",
				"홍익대학교",
				"UNIV_HONGIK",
				"https://www.hongik.ac.kr/kr/education/notice-undergrad.do?mode=list&srCategoryId=24",
				null,
				"articleNo=(\\d+)",
				"https://www.hongik.ac.kr/kr/education/notice-undergrad.do?articleNo={id}&mode=view&srCategoryId=24",
				"article.offset",
				".title",
				".content",
				null,
				5);

		assertThat(site.listPageUrl(1)).contains("article.offset=0");
		assertThat(site.listPageUrl(2)).contains("article.offset=10");
		assertThat(site.listPageUrl(3)).contains("article.offset=20");
	}

	@Test
	@DisplayName("홍익대 목록 링크는 mode/view 순서와 무관하게 articleNo 를 추출한다")
	void extractsHongikArticleNoRegardlessQueryOrder() {
		var pattern = java.util.regex.Pattern.compile(
				"^((?:/kr/(?:education/notice-undergrad|newscenter/notice)\\.do)?\\?mode=view&articleNo=\\d+[^\"'\\s]*)");

		var modeFirst = pattern.matcher("?mode=view&articleNo=521046&article.offset=0");
		var newsCenter = pattern.matcher("/kr/newscenter/notice.do?mode=view&articleNo=521046&noCat=501");
		var otherArticleNo = pattern.matcher("/kr/education/open-education.do?mode=view&articleNo=5418");

		assertThat(modeFirst.find()).isTrue();
		assertThat(modeFirst.group(1)).startsWith("?mode=view&articleNo=521046");
		assertThat(newsCenter.find()).isTrue();
		assertThat(newsCenter.group(1)).startsWith("/kr/newscenter/notice.do?mode=view&articleNo=521046");
		assertThat(otherArticleNo.find()).isFalse();
	}

	@Test
	@DisplayName("홍익대 상세 링크는 상대/절대 경로 그대로 보정하고 sourceId 는 articleNo 로 안정화한다")
	void buildsHongikDetailUrlFromHref() {
		var site = new UnivNoticeProperties.Site(
				"hongik",
				"홍익대학교",
				"UNIV_HONGIK",
				"https://www.hongik.ac.kr/kr/education/notice-undergrad.do?mode=list&srCategoryId=24",
				null,
				"articleNo=(\\d+)",
				null,
				"article.offset",
				".title",
				".content",
				null,
				5);

		assertThat(site.detailUrl("https://www.hongik.ac.kr",
				"?mode=view&articleNo=154747&article.offset=0"))
				.isEqualTo("https://www.hongik.ac.kr/kr/education/notice-undergrad.do?mode=view&articleNo=154747&article.offset=0");
		assertThat(site.detailUrl("https://www.hongik.ac.kr",
				"/kr/newscenter/notice.do?mode=view&articleNo=154746&noCat=501"))
				.isEqualTo("https://www.hongik.ac.kr/kr/newscenter/notice.do?mode=view&articleNo=154746&noCat=501");
		assertThat(site.sourceIdOf("?mode=view&articleNo=154747&article.offset=0")).isEqualTo("154747");
	}

	@Test
	@DisplayName("숭실대 slug 상세 링크는 전체 URL을 그대로 사용하고 긴 sourceId 는 해시로 줄인다")
	void buildsSsuDetailUrlFromHrefAndShortensLongSlugSourceId() {
		var site = new UnivNoticeProperties.Site(
				"ssu",
				"숭실대학교",
				"UNIV_SSU",
				"https://scatch.ssu.ac.kr/%EA%B3%B5%EC%A7%80%EC%82%AC%ED%95%AD/?category=%EC%9E%A5%ED%95%99",
				null,
				"^(https://scatch\\.ssu\\.ac\\.kr/[^\"'\\s]*[?&]slug=[^\"'\\s&]+[^\"'\\s]*)",
				null,
				"paged",
				".bg-white h1, h1",
				".bg-white, .entry-content",
				null,
				10);
		String href = "https://scatch.ssu.ac.kr/%ea%b3%b5%ec%a7%80%ec%82%ac%ed%95%ad/"
				+ "?f&category=%EC%9E%A5%ED%95%99&paged=1&slug="
				+ "2026%ED%95%99%EB%85%84%EB%8F%84-2%ED%95%99%EA%B8%B0-%EC%A4%91%EC%86%8C%EA%B8%B0%EC%97%85-"
				+ "%EC%B7%A8%EC%97%85%EC%97%B0%EA%B3%84-%EC%9E%A5%ED%95%99%EA%B8%88%ED%9D%AC%EB%A7%9D"
				+ "&keyword";

		assertThat(site.detailUrl("https://scatch.ssu.ac.kr", href)).isEqualTo(href);
		assertThat(site.sourceIdOf(href)).startsWith("2026%ED%95%99%EB%85%84%EB%8F%84");

		String longHref = "https://scatch.ssu.ac.kr/%ea%b3%b5%ec%a7%80%ec%82%ac%ed%95%ad/"
				+ "?f&category=%EC%9E%A5%ED%95%99&paged=1&slug=" + "a".repeat(181) + "&keyword";
		assertThat(site.sourceIdOf(longHref)).hasSize(64);
	}

	@Test
	@DisplayName("본문 'YYYY. M. D. ~ M. D.' 기간을 추출한다 (뒤 연도 생략 시 앞 연도 승계)")
	void parsesPeriodWithLeadingYear() {
		var period = UnivNoticeCollector.parsePeriod(
				"신청기간: 2026. 7. 28. ~ 8. 19.(수요일 17시까지)", 2026);

		assertThat(period).isNotNull();
		assertThat(period.start()).isEqualTo(LocalDateTime.of(2026, 7, 28, 0, 0));
		assertThat(period.end().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 19));
	}

	@Test
	@DisplayName("제목 괄호형 '(7. 28. ~ 8. 19.)'은 기본 연도로 추출한다")
	void parsesPeriodWithoutYear() {
		var period = UnivNoticeCollector.parsePeriod(
				"[교외][등록금] 장학생 선발 안내(7. 28. ~ 8. 19.)", 2026);

		assertThat(period).isNotNull();
		assertThat(period.start().getYear()).isEqualTo(2026);
		assertThat(period.start().getMonthValue()).isEqualTo(7);
		assertThat(period.end().getMonthValue()).isEqualTo(8);
	}

	@Test
	@DisplayName("연말~연초 범위(12월~1월)는 종료 연도를 +1 처리한다")
	void handlesYearRollover() {
		var period = UnivNoticeCollector.parsePeriod("모집: 2026. 12. 20. ~ 1. 10.", 2026);

		assertThat(period).isNotNull();
		assertThat(period.end().getYear()).isEqualTo(2027);
	}

	@Test
	@DisplayName("하이픈 날짜 범위 'YYYY-MM-DD ~ YYYY-MM-DD'를 추출한다")
	void parsesHyphenPeriod() {
		var period = UnivNoticeCollector.parsePeriod("신청기간 : 2026-07-01 ~ 2026-07-31", 2026);

		assertThat(period).isNotNull();
		assertThat(period.start()).isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
		assertThat(period.end().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 7, 31));
	}

	@Test
	@DisplayName("요일/시간이 끼어 있는 범위 '8.3.월 10시~8.10.월 15시'를 추출한다")
	void parsesPeriodWithWeekdayAndTime() {
		var period = UnivNoticeCollector.parsePeriod(
				"서울인재대학장학금 선발 안내(1학년 대상, 8.3.월 10시~8.10.월 15시)", 2026);

		assertThat(period).isNotNull();
		assertThat(period.start()).isEqualTo(LocalDateTime.of(2026, 8, 3, 0, 0));
		assertThat(period.end().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 10));
	}

	@Test
	@DisplayName("동국대 표 본문형 신청기간 '2026.06.08.(월) 10:00 ~ 2026.07.08.(수) 16:00까지'를 추출한다")
	void parsesDonggukTablePeriod() {
		var period = UnivNoticeCollector.parsePeriod(
				"신청기간 2026.06.08.( 월 ) 10:00 ~ 2026.07.08.( 수 ) 16:00 까지", 2026);

		assertThat(period).isNotNull();
		assertThat(period.start()).isEqualTo(LocalDateTime.of(2026, 6, 8, 0, 0));
		assertThat(period.end()).isEqualTo(LocalDateTime.of(2026, 7, 8, 16, 0, 59));
	}

	@Test
	@DisplayName("종료일만 있는 '~4/16(목) 17시까지' 표기는 마감일만 추출한다")
	void parsesDeadlineOnlyShortDate() {
		var period = UnivNoticeCollector.parsePeriod(
				"동국여자총동창회 장학생 선발 안내(~4/16(목) 17시까지, 1인당 150만원)", 2026);

		assertThat(period).isNotNull();
		assertThat(period.start()).isNull();
		assertThat(period.end().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 4, 16));
	}

	@Test
	@DisplayName("동국대식 '~7.21.(화) 23:59까지' 마감일을 추출한다")
	void parsesDonggukDeadlineWithTrailingDotAndWeekday() {
		var period = UnivNoticeCollector.parsePeriod("8. 신청기한 : ~7.21.( 화 ) 23:59 까지", 2026);

		assertThat(period).isNotNull();
		assertThat(period.start()).isNull();
		assertThat(period.end()).isEqualTo(LocalDateTime.of(2026, 7, 21, 23, 59, 59));
	}

	@Test
	@DisplayName("종료일만 있는 '2026년 8월 10일까지' 표기는 마감일만 추출한다")
	void parsesDeadlineOnlyKoreanDate() {
		var period = UnivNoticeCollector.parsePeriod("신청기한: 2026년 8월 10일(월)까지", 2026);

		assertThat(period).isNotNull();
		assertThat(period.start()).isNull();
		assertThat(period.end().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 10));
	}

	@Test
	@DisplayName("동국대 제출서류 섹션에서 서류명을 추출한다")
	void extractsDonggukDocumentNames() {
		var documents = UnivNoticeCollector.extractDocumentNames("""
				7. 신청방법 및 제출서류
				① 신청방법 : nDRIMS 접속 후 장학신청
				② 제출서류: (한부모가정 학생만 해당)
				한부모 증명서 1부 또는 가족관계증명서 및 혼인관계 증명서 각 1부
				8. 신청기한 : ~7.21.(화) 23:59까지
				""");

		assertThat(documents)
				.contains("한부모 증명서 1부 또는 가족관계증명서 및 혼인관계 증명서 각 1부");
	}

	@Test
	@DisplayName("자기소개서/학업계획서 계열 제출서류를 추출한다")
	void extractsEssayDocumentNames() {
		var documents = UnivNoticeCollector.extractDocumentNames(
				"제출서류: 장학금 신청서, 자기소개서, 학업계획서, 성적증명서 신청기간: 별도 안내");

		assertThat(documents).contains("장학금 신청서", "자기소개서", "학업계획서", "성적증명서");
	}

	@Test
	@DisplayName("본문 인라인 이미지를 포스터 후보로 찾고, 로고/아이콘은 제외한다")
	void findsPosterFromInlineImage() {
		org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse("""
				<html><body>
				<img src="https://u.ac.kr/common/logo.png">
				<div class="artclView"><img src="https://u.ac.kr/upload/poster1.jpg"></div>
				</body></html>""", "https://u.ac.kr/");
		org.assertj.core.api.Assertions.assertThat(UnivNoticeCollector.findPosterUrl(doc))
				.isEqualTo("https://u.ac.kr/upload/poster1.jpg");
	}

	@Test
	@DisplayName("홍익대/숭실대처럼 본문이 이미지만 있는 공지도 본문 이미지 후보를 찾는다")
	void findsPosterFromContentOnlyImage() {
		org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse("""
				<html><head>
				<meta property="og:image" content="https://scatch.ssu.ac.kr/wp-content/themes/SSUcatch/assets/images/ssu_ogimage.jpg">
				</head><body>
				<div class="bg-white">
					<img src="/wp-content/uploads/sites/5/2026/08/scholarship-poster.png">
				</div>
				</body></html>""", "https://scatch.ssu.ac.kr/");
		org.assertj.core.api.Assertions.assertThat(UnivNoticeCollector.findPosterUrl(doc))
				.isEqualTo("https://scatch.ssu.ac.kr/wp-content/uploads/sites/5/2026/08/scholarship-poster.png");
	}

	@Test
	@DisplayName("동국대 공통 og 이미지와 헤더 로고는 포스터 후보에서 제외한다")
	void ignoresDonggukCommonImages() {
		org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse("""
				<html><head>
				<meta property="og:image" content="https://www.dongguk.edu/resources/files/og_thumbnail.jpg?v=01">
				</head><body>
				<img src="/resources/images/site/common/header_logo.png">
				<div class="board_view">
					<div class="view_cont"><img src="/upload/scholarship/poster.jpg"></div>
				</div>
				</body></html>""", "https://www.dongguk.edu/");
		org.assertj.core.api.Assertions.assertThat(UnivNoticeCollector.findPosterUrl(doc))
				.isEqualTo("https://www.dongguk.edu/upload/scholarship/poster.jpg");
	}

	@Test
	@DisplayName("이미지 첨부파일(.jpg 링크명)을 포스터 후보로 찾는다")
	void findsPosterFromAttachment() {
		org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse("""
				<html><body>
				<div class="board_view"><div class="view_cont">
					<a href="/bbs/u/1/100/download.do">모집요강.pdf</a>
					<a href="/bbs/u/1/101/download.do">포스터.jpg</a>
				</div></div>
				</body></html>""", "https://u.ac.kr/");
		org.assertj.core.api.Assertions.assertThat(UnivNoticeCollector.findPosterUrl(doc))
				.isEqualTo("https://u.ac.kr/bbs/u/1/101/download.do");
	}

	@Test
	@DisplayName("포스터 후보가 없으면 null")
	void posterNullWhenNone() {
		org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(
				"<html><body><a href='/bbs/u/1/1/download.do'>안내.hwp</a></body></html>", "https://u.ac.kr/");
		org.assertj.core.api.Assertions.assertThat(UnivNoticeCollector.findPosterUrl(doc)).isNull();
	}

	@Test
	@DisplayName("제목: hidden input(연세형) 우선, 없으면 제목 클래스(건국형), h2 폴백")
	void extractsTitleBySkin() {
		var yonsei = org.jsoup.Jsoup.parse(
				"<html><body><h2>Yonsei University</h2>"
						+ "<input type=\"hidden\" id=\"artclViewTitle\" value=\"장학생 선발 안내\"></body></html>");
		org.assertj.core.api.Assertions.assertThat(UnivNoticeCollector.extractTitle(yonsei))
				.isEqualTo("장학생 선발 안내");

		var konkuk = org.jsoup.Jsoup.parse(
				"<html><body><h2 class=\"artclViewTitle\">[교외] 장학 안내</h2></body></html>");
		org.assertj.core.api.Assertions.assertThat(UnivNoticeCollector.extractTitle(konkuk))
				.isEqualTo("[교외] 장학 안내");
	}

	@Test
	@DisplayName("제목: 분류 라벨을 자식 요소로 넣는 스킨(인천대형)에서 라벨을 제외한다")
	void extractsTitleExcludingCategoryLabel() {
		var inu = org.jsoup.Jsoup.parse("""
				<html><body>
				<h2 class="view-title">
					<span class="hidden">분류</span>
					<span>[교외장학금]</span>
					[교외장학] 2026년도 드림재단 장학생 선발 안내
				</h2>
				</body></html>
				""");
		org.assertj.core.api.Assertions.assertThat(UnivNoticeCollector.extractTitle(inu))
				.isEqualTo("[교외장학] 2026년도 드림재단 장학생 선발 안내");
	}

	@Test
	@DisplayName("제목: 제목이 자식 요소 안에만 있으면 기존대로 전체 텍스트를 쓴다")
	void extractsTitleFromChildElementWhenNoOwnText() {
		var doc = org.jsoup.Jsoup.parse(
				"<html><body><div class=\"view-title\"><span>2026-2 교내장학금 신청</span></div></body></html>");
		org.assertj.core.api.Assertions.assertThat(UnivNoticeCollector.extractTitle(doc))
				.isEqualTo("2026-2 교내장학금 신청");
	}

	@Test
	@DisplayName("제목: og:title 폴백 시 사이트명 접두를 제거한다")
	void extractsTitleFromOgWithPrefixStrip() {
		var doc = org.jsoup.Jsoup.parse(
				"<html><head><meta property=\"og:title\" content=\"홍익대학교 | 2026-2 교내장학금 신청 안내\"></head><body></body></html>");
		org.assertj.core.api.Assertions.assertThat(UnivNoticeCollector.extractTitle(doc))
				.isEqualTo("2026-2 교내장학금 신청 안내");
	}




	@Test
	@DisplayName("기간 표기가 없으면 null")
	void returnsNullWhenNoPeriod() {
		assertThat(UnivNoticeCollector.parsePeriod("장학생 선발 안내", 2026)).isNull();
	}

	@Test
	@DisplayName("장학 아닌 공지는 상세의 분류로 걸러낸다 — 목록 필터는 고정 공지에 안 먹는다")
	void filtersOutNonScholarshipByCategory() {
		// 연세대 스킨: 제목 아래 목록에 분류를 넣는다. "분류" 라벨은 화면에 안 보이는 글자다.
		String detail = """
				<html><body><div class="view viewCont"><div class="title">
				  <strong>2026학년도 여름방학 셔틀버스 운행 시간표</strong>
				  <ul class="detail">
				    <li class="cl"><span class="hidden">분류</span> [일반]</li>
				    <li><span>작성자</span> 총무팀</li>
				  </ul>
				</div></div></body></html>
				""";
		var site = siteWithCategory("장학");

		assertThat(site.acceptsCategory(
				UnivNoticeCollector.extractCategory(org.jsoup.Jsoup.parse(detail)))).isFalse();
	}

	@Test
	@DisplayName("장학 공지는 통과시킨다")
	void keepsScholarshipCategory() {
		String detail = """
				<html><body><div class="view viewCont"><div class="title">
				  <strong>2026-2 교내장학 신청 안내</strong>
				  <ul class="detail"><li class="cl"><span class="hidden">분류</span> [장학]</li></ul>
				</div></div></body></html>
				""";

		assertThat(siteWithCategory("장학").acceptsCategory(
				UnivNoticeCollector.extractCategory(org.jsoup.Jsoup.parse(detail)))).isTrue();
	}

	@Test
	@DisplayName("분류를 못 읽으면 거르지 않는다 — 스킨이 바뀌었을 때 전부 사라지는 게 더 나쁘다")
	void keepsNoticeWhenCategoryIsUnreadable() {
		String detail = "<html><body><div class='artclView'>분류 표기가 없는 스킨</div></body></html>";

		assertThat(siteWithCategory("장학").acceptsCategory(
				UnivNoticeCollector.extractCategory(org.jsoup.Jsoup.parse(detail)))).isTrue();
	}

	@Test
	@DisplayName("분류를 지정하지 않은 게시판은 그대로 다 받는다 — 장학 전용 게시판이 대부분이다")
	void doesNotFilterWhenNoCategoryConfigured() {
		assertThat(siteWithCategory(null).acceptsCategory("[일반]")).isTrue();
	}

	private UnivNoticeProperties.Site siteWithCategory(String includeCategory) {
		return new UnivNoticeProperties.Site("yonsei", "연세대학교", "UNIV_YONSEI",
				"https://example.com/list", "/bbs/sc/58/", null, null, null,
				null, null, includeCategory, 10);
	}

	/*
	수집기는 원본만 저장한다. 정제(scholarship·조건·서류)는 LLM 파싱이 전담한다.

	예전에는 수집기가 정규식으로 제목·기간·조건을 뽑아 scholarship 을 만들고, 나중에 LLM 파싱이
	그 위를 덮어썼다. 같은 일을 두 번 하는 데다 LLM 파싱은 수동 트리거라, 누가 누르기 전까지
	사용자에게는 정규식 결과가 노출됐다. 정규식이 LLM 보다 잘할 리 없다.
	 */
	@Test
	@DisplayName("수집기는 scholarship 을 만들지 않는다 — 정제는 LLM 파싱이 전담한다")
	void collectorNeverCreatesScholarship() throws Exception {
		var collectorSource = java.nio.file.Files.readString(java.nio.file.Path.of(
				"src/main/java/com/wishconnect/domain/scholarship/collector/UnivNoticeCollector.java"));

		assertThat(collectorSource).doesNotContain("scholarshipRepository.save");
		assertThat(collectorSource).doesNotContain("storeConditions");
		assertThat(collectorSource).doesNotContain("markParsed");
	}

	@Test
	@DisplayName("수집 결과는 언제나 PENDING 이다 — 마감 판정을 수집기가 하지 않는다")
	void collectedNoticesWaitForParsing() throws Exception {
		var collectorSource = java.nio.file.Files.readString(java.nio.file.Path.of(
				"src/main/java/com/wishconnect/domain/scholarship/collector/UnivNoticeCollector.java"));

		// PENDING 이라야 reparse 없이도 파싱 대상이 된다.
		assertThat(collectorSource).contains(".parseStatus(ParseStatus.PENDING)");
		// 정규식 마감 판정은 연도를 못 읽어 올해로 가정했고, 모집 중인 공고를 버렸다.
		// 한 배치에서 26건이 되살아났다. 기간 판단은 근거를 대조하는 LLM 파싱만 한다.
		assertThat(collectorSource).doesNotContain("boolean closed");
		assertThat(collectorSource).doesNotContain("모집종료일이 지난 공지입니다.");
	}
}
