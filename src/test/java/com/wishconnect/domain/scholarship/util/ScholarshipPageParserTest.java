package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 기관 상세페이지에서 포스터·첨부파일을 뽑는 규칙. 사이트 구조가 제각각이라 표준 신호만 쓴다. */
class ScholarshipPageParserTest {

	private Document parse(String html) {
		return Jsoup.parse(html, "https://www.gwgs.go.kr/board/");
	}

	@Test
	@DisplayName("og:image 를 포스터로 쓴다")
	void prefersOgImage() {
		Document doc = parse("""
				<html><head><meta property="og:image" content="https://cdn.gwgs.go.kr/poster.jpg"></head>
				<body><img src="/img/content.png"></body></html>
				""");
		assertThat(ScholarshipPageParser.findPosterImageUrl(doc))
				.isEqualTo("https://cdn.gwgs.go.kr/poster.jpg");
	}

	/** 기관 사이트는 헤더 로고가 항상 첫 이미지라, 안 거르면 전부 로고가 포스터가 된다. */
	@Test
	@DisplayName("로고·배너·아이콘은 포스터로 쓰지 않는다")
	void skipsLogoAndBanner() {
		Document doc = parse("""
				<html><body>
				<img src="/img/logo.png"><img src="/img/top_banner.gif"><img src="/upload/poster_2026.jpg">
				</body></html>
				""");
		assertThat(ScholarshipPageParser.findPosterImageUrl(doc)).endsWith("/upload/poster_2026.jpg");
	}

	@Test
	@DisplayName("og:image 자체가 로고면 본문 이미지로 넘어간다")
	void fallsBackWhenOgImageIsLogo() {
		Document doc = parse("""
				<html><head><meta property="og:image" content="https://cdn.gwgs.go.kr/common/logo.png"></head>
				<body><img src="/upload/poster.jpg"></body></html>
				""");
		assertThat(ScholarshipPageParser.findPosterImageUrl(doc)).endsWith("/upload/poster.jpg");
	}

	@Test
	@DisplayName("이미지가 없으면 null")
	void nullWhenNoImage() {
		assertThat(ScholarshipPageParser.findPosterImageUrl(parse("<html><body>내용</body></html>"))).isNull();
	}

	@Test
	@DisplayName("문서 확장자 링크를 첨부파일로 잡고 절대경로로 만든다")
	void findsDocumentAttachments() {
		Document doc = parse("""
				<html><body>
				<a href="/files/신청서.hwp">장학금 신청서</a>
				<a href="/files/공고문.pdf">모집 공고문</a>
				<a href="/board/list.do">목록으로</a>
				</body></html>
				""");
		List<ScholarshipPageParser.Attachment> attachments = ScholarshipPageParser.findAttachments(doc);

		assertThat(attachments).hasSize(2);
		assertThat(attachments.get(0).name()).isEqualTo("장학금 신청서");
		assertThat(attachments.get(0).downloadUrl()).isEqualTo("https://www.gwgs.go.kr/files/신청서.hwp");
	}

	/** 눌렀을 때 엉뚱한 페이지가 열리는 건 링크가 없는 것보다 나쁘다. */
	@Test
	@DisplayName("확장자를 알 수 없는 다운로드 경로는 첨부로 잡지 않는다")
	void skipsUnknownDownloadPaths() {
		Document doc = parse("""
				<html><body><a href="/cmm/fms/FileDown.do?fileId=1234">첨부파일 내려받기</a></body></html>
				""");
		assertThat(ScholarshipPageParser.findAttachments(doc)).isEmpty();
	}

	@Test
	@DisplayName("링크 텍스트에 확장자가 있으면 잡는다")
	void detectsExtensionFromLinkText() {
		Document doc = parse("""
				<html><body><a href="/download?id=9">2026_장학생_모집공고.pdf</a></body></html>
				""");
		assertThat(ScholarshipPageParser.findAttachments(doc)).hasSize(1);
	}

	@Test
	@DisplayName("같은 링크가 여러 번 나와도 한 번만 잡는다")
	void deduplicatesSameHref() {
		Document doc = parse("""
				<html><body>
				<a href="/files/a.hwp">신청서</a><a href="/files/a.hwp">신청서(재게시)</a>
				</body></html>
				""");
		assertThat(ScholarshipPageParser.findAttachments(doc)).hasSize(1);
	}
}
