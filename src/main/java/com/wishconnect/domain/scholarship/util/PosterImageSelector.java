package com.wishconnect.domain.scholarship.util;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** 두 수집 경로가 같은 규칙으로 공고 본문의 포스터를 고른다. */
public final class PosterImageSelector {

    private static final String CONTENT = ".notice_tb_view_contents, .fr-view, .view_cont, "
            + ".hwp_editor_board_content, .artclView, .board-view, .board02 .row.contents, "
            + ".row.contents, .entry-content, .board_view, .bbs_view, .view-con, .view_content, "
            + ".b-content, .board_cont, .article-view, article, main";
    private static final String CHROME = "header, footer, nav, aside, .gnb, .lnb, .snb, .menu";
    private static final Pattern NOISE = Pattern.compile(
            "(?i)logo|banner|icon|btn|button|sprite|blank|spacer|profile|favicon|"
                    + "og_thumbnail|ogimage|ui[_-]?mark|symbol|emblem|로고|배너|아이콘|/common/|/header/|/footer/");
    private static final Pattern IMAGE_EXTENSION = Pattern.compile("(?i)\\.(png|jpe?g|gif|webp)(?:\\?.*)?$");

    private PosterImageSelector() { }

    public static String find(Document document) {
        // 공고 본문이 있는 사이트에서는 사이트 공유용 og:image 보다 본문·첨부를 우선한다.
        for (Element root : document.select(CONTENT)) {
            String candidate = fromContent(root);
            if (candidate != null) return candidate;
        }
        for (Element meta : document.select("meta[property=og:image], meta[name=og:image]")) {
            String candidate = meta.absUrl("content");
            // 본문이 없는 메타 이미지는 공고 포스터라는 명시적인 신호가 있어야 쓴다.
            if (acceptable(candidate, meta)
                    && candidate.toLowerCase(Locale.ROOT).matches(".*(poster|포스터|장학생|scholarship).*")) {
                return candidate;
            }
        }
        // 알려진 본문 구조가 없는 페이지는 포스터라는 파일명 신호가 있는 이미지만 허용한다.
        if (document.select(CONTENT).isEmpty()) {
            for (Element image : document.select("img[src]")) {
                String candidate = image.absUrl("src");
                if (acceptable(candidate, image) && candidate.toLowerCase(Locale.ROOT).contains("poster")
                        && !small(image)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String fromContent(Element root) {
        for (Element img : root.select("img[src], img[data-src]")) {
            String candidate = img.absUrl("src");
            if (!acceptable(candidate, img)) candidate = img.absUrl("data-src");
            if (acceptable(candidate, img) && !small(img)) return candidate;
        }
        for (Element link : root.select("a[href]")) {
            String candidate = link.absUrl("href");
            if (acceptable(candidate, link)
                    && (IMAGE_EXTENSION.matcher(candidate).find()
                        || IMAGE_EXTENSION.matcher(link.text().trim()).find())) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean acceptable(String url, Element element) {
        if (url == null || url.isBlank() || NOISE.matcher(url).find()
                || NOISE.matcher(element.attr("alt") + " " + element.id() + " " + element.className()).find()) {
            return false;
        }
        for (Element parent : element.parents()) {
            if (parent.is(CHROME)) return false;
        }
        try {
            URI uri = URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean small(Element image) {
        for (String dimension : new String[]{"width", "height"}) {
            String value = image.attr(dimension);
            if (value.matches("\\d{1,9}") && Integer.parseInt(value) < 100) return true;
        }
        return false;
    }
}
