package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.StreamUtils;

/**
 * 공지 HTML 에서 제목·본문 골라내기.
 *
 * <p>고정 자료는 <b>운영에서 실제로 잘못 파싱된 그 게시글들</b>이다. 수집 당시 저장해 둔 HTML 을
 * 그대로 넣었다(script·style 만 걷어냄). 전수조사 100건에서 35건이 본문 대신 사이트 메뉴를,
 * 26건이 제목 대신 공유버튼 문구를 담고 있었고, 그 대표 사례들이다.
 */
class NoticeHtmlExtractorTest {

	private Document load(String id) throws Exception {
		try (var in = getClass().getResourceAsStream("/notice/" + id + ".html")) {
			return Jsoup.parse(StreamUtils.copyToString(in, StandardCharsets.UTF_8));
		}
	}

	@Test
	@DisplayName("본문 영역을 못 찾아도 페이지 전체로 폴백하지 않는다")
	void neverFallsBackToWholePage() {
		Document doc = Jsoup.parse("""
				<html><body>
				  <nav>주메뉴 바로가기 본문내용 바로가기 푸터 바로가기 대학소개 교육이념 건학이념</nav>
				  <div id="etc">본문 영역이 없는 페이지</div>
				</body></html>
				""");

		assertThat(NoticeHtmlExtractor.body(doc, null)).isEmpty();
	}

	@Test
	@DisplayName("앞 후보가 비어 있으면 다음 후보를 본다 — 서울시립대가 이 경우였다")
	void skipsEmptyCandidateAndTakesTheNext() throws Exception {
		// .hwp_editor_board_content(빈 값)가 .notice_tb_view_contents 보다 먼저 걸린다.
		Optional<String> body = NoticeHtmlExtractor.body(load("1983"), null);

		assertThat(body).isPresent();
		assertThat(body.get()).contains("2026학년도 제2학기 학부 교내장학 2차 신청 공고");
		assertThat(NoticeHtmlExtractor.looksLikeChrome(body.get())).isFalse();
	}

	@Test
	@DisplayName("본문 대신 대학 소개문이 들어가던 게시글들이 이제 본문을 집는다")
	void picksArticleBodyInsteadOfSiteChrome() throws Exception {
		assertThat(NoticeHtmlExtractor.body(load("2010"), null))   // 세종대
				.get().asString().contains("국가장학금 신청이 아래와 같이");
		assertThat(NoticeHtmlExtractor.body(load("4160"), null))   // 동국대
				.get().asString().contains("월드머시코리아장학생");
		assertThat(NoticeHtmlExtractor.body(load("4041"), null))   // 국민대(원래 정상) — 회귀 방지
				.get().asString().contains("국가근로장학금 2차 신청기간");
	}

	@Test
	@DisplayName("공유버튼 문구가 제목이 되던 게시글들이 진짜 제목을 집는다")
	void picksRealTitleInsteadOfShareButtons() throws Exception {
		assertThat(NoticeHtmlExtractor.title(load("4050"), null))   // 홍익대: 19건 전부 이 문제였다
				.contains("[서울캠퍼스] 2026년도 2학기 국가근로 및 교내봉사 장학생 선발안내");
		assertThat(NoticeHtmlExtractor.title(load("1983"), null))   // 서울시립대
				.contains("[교내/신청] 2026학년도 제2학기 학부 교내장학 2차 신청 공고");
	}

	@Test
	@DisplayName("제목에 붙은 작성일·조회수는 떼어낸다")
	void stripsBoardMetaFromTitle() throws Exception {
		assertThat(NoticeHtmlExtractor.title(load("4160"), null))   // 동국대
				.contains("2026학년도 2학기 월드머시코리아장학생 선발 안내(~8/17(월))");
		assertThat(NoticeHtmlExtractor.title(load("1886"), null))   // 한림대
				.contains("[의학교육학교실] 2026-하계 국가근로 장학생 모집 (1명) (모집마감)");
	}

	@Test
	@DisplayName("한림대 본문에는 깨진 글자가 섞이지 않는다 — 깨진 건 페이지 문서 제목뿐이었다")
	void hallymBodyHasNoMojibake() throws Exception {
		Optional<String> body = NoticeHtmlExtractor.body(load("1886"), null);

		assertThat(body).isPresent();
		assertThat(body.get()).doesNotContain("�");
	}

	@Test
	@DisplayName("메뉴가 섞였는지 알아본다 — 다음 게시판에서 또 빗나갔을 때 드러나게 하는 안전망")
	void detectsSiteChrome() {
		assertThat(NoticeHtmlExtractor.looksLikeChrome(
				"주메뉴 바로가기 본문내용 바로가기 푸터 바로가기 국민대학교 대학소개")).isTrue();
		assertThat(NoticeHtmlExtractor.looksLikeChrome(
				"2026학년도 2학기 국가근로장학금 2차 신청 안내 1. 신청기간")).isFalse();
		// 한 개만 걸리는 건 본문에도 흔한 말이라 넘긴다.
		assertThat(NoticeHtmlExtractor.looksLikeChrome("신청 바로가기 를 눌러 접수하세요")).isFalse();
	}

	@Test
	@DisplayName("본문이 포스터 이미지뿐이면 '내용 없음'이 아니라 '이미지 전용'으로 구분한다")
	void marksImageOnlyNoticesSeparately() throws Exception {
		Document hongik = load("4051");   // .fr-view 안에 포스터 이미지 한 장뿐

		assertThat(NoticeHtmlExtractor.body(hongik, null)).isEmpty();
		assertThat(NoticeHtmlExtractor.imageOnly(hongik, null)).isTrue();
		// 글이 있는 공지는 이미지가 섞여 있어도 이미지 전용이 아니다.
		assertThat(NoticeHtmlExtractor.imageOnly(load("4041"), null)).isFalse();
	}

	@Test
	@DisplayName("이미지 설명(alt)에 내용이 있으면 그것을 본문으로 쓴다")
	void usesImageAltWhenItCarriesTheContent() throws Exception {
		// 한국외대는 접근성 때문에 포스터 내용을 alt 에 적어 둔다 — 모집기간까지 들어 있다.
		Optional<String> body = NoticeHtmlExtractor.body(load("1951"), null);

		assertThat(body).isPresent();
		assertThat(body.get()).contains("한국장학재단 학자금 대출 신청 안내");
		assertThat(body.get()).contains("11월 17일");
		assertThat(NoticeHtmlExtractor.imageOnly(load("1951"), null)).isFalse();
	}

	@Test
	@DisplayName("전용 수집기를 쓰는 게시판도 본문을 집는다 — LLM 파서는 출처별 설정을 모른다")
	void handlesBoardsOwnedByDedicatedCollectors() throws Exception {
		// 파서는 raw_html 만 받으므로 사이트별 셀렉터를 쓸 수 없다. 공용 후보에 들어 있어야 한다.
		assertThat(NoticeHtmlExtractor.body(load("4282"), null))   // 경희대
				.get().asString().contains("경희인턴장학");
		assertThat(NoticeHtmlExtractor.body(load("4060"), null))   // 숭실대(워드프레스)
				.get().asString().contains("학자금대출 이자 지원");
	}
}
