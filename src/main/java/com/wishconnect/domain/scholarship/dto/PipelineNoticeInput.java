package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.util.KosafNoticeText;
import com.wishconnect.domain.scholarship.util.NoticeHtmlExtractor;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;

/** 비교 실행 시 사용한 원본과 정제 입력. 운영 정제 테이블과 독립적으로 보존한다. */
public record PipelineNoticeInput(
        Long rawId, Long scholarshipId, String source, String sourceUrl, String title,
        String body, List<String> attachments, LocalDate referenceDate,
        String rawHtml, Map<String, Object> rawJson, boolean truncated) {

    private static final int MAX_BODY = 20_000;

    public static PipelineNoticeInput html(Long rawId, Long scholarshipId, String source, String url,
            String fallbackTitle, String html, LocalDate referenceDate) {
        var document = Jsoup.parse(html == null ? "" : html, url == null ? "" : url);
        document.select("script, style, noscript, nav, header, footer, aside").remove();
        String title = NoticeHtmlExtractor.title(document, null).orElse(fallbackTitle);
        String body = NoticeHtmlExtractor.body(document, null).orElse("");
        return new PipelineNoticeInput(rawId, scholarshipId, source, url, title,
                body.substring(0, Math.min(body.length(), MAX_BODY)),
                NoticeHtmlExtractor.attachmentNames(document), referenceDate,
                html, null, body.length() > MAX_BODY);
    }

    public static PipelineNoticeInput api(Long rawId, Long scholarshipId, String source, String url,
            Map<String, Object> json, LocalDate referenceDate) {
        String body = KosafNoticeText.bodyOf(json);
        return new PipelineNoticeInput(rawId, scholarshipId, source, url, KosafNoticeText.titleOf(json),
                body.substring(0, Math.min(body.length(), MAX_BODY)), List.of(), referenceDate,
                null, json, body.length() > MAX_BODY);
    }

    public String evidenceText() {
        return (title == null ? "" : title) + "\n" + (body == null ? "" : body);
    }
}
