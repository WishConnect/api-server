package com.wishconnect.domain.scholarship.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UnivNoticeCollectorTest {

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
	@DisplayName("이미지 첨부파일(.jpg 링크명)을 포스터 후보로 찾는다")
	void findsPosterFromAttachment() {
		org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse("""
				<html><body>
				<a href="/bbs/u/1/100/download.do">모집요강.pdf</a>
				<a href="/bbs/u/1/101/download.do">포스터.jpg</a>
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
	@DisplayName("[교외]/[학교추천]/[국가] 태그는 EXTERNAL + 제목에서 재단명 추출")
	void classifiesExternalByTag() {
		var ext = UnivNoticeCollector.classify(
				"[교외][생활비] 2026년 정읍시민장학재단 우수인재 장학생 모집", "건국대학교");
		org.assertj.core.api.Assertions.assertThat(ext.type())
				.isEqualTo(com.wishconnect.domain.scholarship.entity.ScholarshipType.EXTERNAL);
		org.assertj.core.api.Assertions.assertThat(ext.provider()).isEqualTo("정읍시민장학재단");

		var rec = UnivNoticeCollector.classify(
				"[학교추천][교외] 광진구장학회 장학생 추천 선발 안내", "건국대학교");
		org.assertj.core.api.Assertions.assertThat(rec.provider()).isEqualTo("광진구장학회");

		var noProvider = UnivNoticeCollector.classify("[국가근로] 2026-1 국가근로장학금 안내", "한림대학교");
		org.assertj.core.api.Assertions.assertThat(noProvider.type())
				.isEqualTo(com.wishconnect.domain.scholarship.entity.ScholarshipType.EXTERNAL);
		org.assertj.core.api.Assertions.assertThat(noProvider.provider()).isEqualTo("한림대학교");
	}

	@Test
	@DisplayName("[교내]/무태그 공지는 INTERNAL 유지")
	void keepsInternalWithoutExternalTag() {
		var internal = UnivNoticeCollector.classify("[공통][교내] 2026-2 교내 면학장학금 신청 안내", "한국외국어대학교");
		org.assertj.core.api.Assertions.assertThat(internal.type())
				.isEqualTo(com.wishconnect.domain.scholarship.entity.ScholarshipType.INTERNAL);
		var plain = UnivNoticeCollector.classify("2026학년도 2학기 성적우수장학금 안내", "연세대학교");
		org.assertj.core.api.Assertions.assertThat(plain.type())
				.isEqualTo(com.wishconnect.domain.scholarship.entity.ScholarshipType.INTERNAL);
	}

	@Test
	@DisplayName("기간 표기가 없으면 null")
	void returnsNullWhenNoPeriod() {
		assertThat(UnivNoticeCollector.parsePeriod("장학생 선발 안내", 2026)).isNull();
	}
}
