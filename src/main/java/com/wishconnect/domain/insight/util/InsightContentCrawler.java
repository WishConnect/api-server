package com.wishconnect.domain.insight.util;


import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class InsightContentCrawler {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";

    public String crawl(String url) {
        try {
            if (url.contains("blog.naver.com")) {
                return crawlNaverBlog(url);
            } else if (url.contains("cafe.naver.com")) {
                return crawlNaverCafe(url);
            } else if (url.contains("tistory.com")) {
                return crawlTistory(url);
            }
            log.warn("[Insight] 지원하지 않는 소스 url={}", url);
            return null;
        } catch (IOException e) {
            log.warn("[Insight] 크롤링 실패 url={}", url, e);
            return null;
        }
    }

    private String crawlNaverBlog(String url) throws IOException {
        Document doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(5000)
                .get();

        // 네이버 블로그는 본문이 iframe 안에 있음
        String iframeSrc = doc.select("iframe#mainFrame").attr("src");

        if (!iframeSrc.isBlank()) {
            String realUrl = "https://blog.naver.com" + iframeSrc;
            doc = Jsoup.connect(realUrl)
                    .userAgent(USER_AGENT)
                    .timeout(5000)
                    .get();
        }

        String content = doc.select(".se-main-container").text();

        // 구버전 블로그 스킨 대응 (스마트에디터 3.0 이전)
        if (content.isBlank()) {
            content = doc.select("#postViewArea").text();
        }

        return content;
    }

    private String crawlNaverCafe(String url) throws IOException {
        Document doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(5000)
                .get();

        // 카페도 iframe 구조인 경우가 있음
        String iframeSrc = doc.select("iframe#cafe_main").attr("src");
        if (!iframeSrc.isBlank()) {
            String realUrl = "https://cafe.naver.com" + iframeSrc;
            doc = Jsoup.connect(realUrl)
                    .userAgent(USER_AGENT)
                    .timeout(5000)
                    .get();
        }

        return doc.select(".se-main-container, .ContentRenderer").text();
    }

    private String crawlTistory(String url) throws IOException {
        Document doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(5000)
                .get();

        // 스킨마다 구조가 달라서 여러 후보 선택자 시도
        String content = doc.select("div.article, div.entry-content, div#content, article").text();

        if (content.isBlank()) {
            content = doc.body().text();  // 최후 수단
        }

        return content;
    }
}