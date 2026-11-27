package com.wishconnect.domain.scholarship.util;

import org.jsoup.Jsoup;

/** 공지 제목·본문·링크의 변경 여부를 비교한다. 비교용 본문 길이는 제한하지 않는다. */
public final class NoticeContent {

	private NoticeContent() {
	}

	public static String comparable(String html, String sourceUrl) {
		var doc = Jsoup.parse(html == null ? "" : html, sourceUrl == null ? "" : sourceUrl);
		String links = doc.select("a[href], img[src], img[data-src]").stream()
				.map(e -> e.tagName() + ":" + e.attr("href") + e.attr("src") + e.attr("data-src"))
				.distinct().sorted().collect(java.util.stream.Collectors.joining("\n"));
		return NoticeHtmlExtractor.title(doc, null).orElse("") + "\n"
				+ NoticeHtmlExtractor.body(doc, null).orElse("").replaceAll("\\s+", " ") + "\n" + links;
	}
}
