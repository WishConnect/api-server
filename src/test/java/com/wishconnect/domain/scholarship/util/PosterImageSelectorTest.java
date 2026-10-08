package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class PosterImageSelectorTest {
    @Test
    void bothPipelinesPreferNoticeImagesOverKonkukShareLogo() {
        var doc = Jsoup.parse("""
                <head><meta property="og:image" content="https://www.konkuk.ac.kr/Web-home/UI_Mark_view.png"></head>
                <body><header><img src="/random.png"></header>
                <div class="fr-view"><img src="/CrossEditor/binary/images/20261007.jpg"></div></body>
                """, "https://www.konkuk.ac.kr/bbs/view.do");
        assertThat(NoticeHtmlExtractor.posterUrl(doc))
                .isEqualTo("https://www.konkuk.ac.kr/CrossEditor/binary/images/20261007.jpg");
        assertThat(ScholarshipPageParser.findPosterImageUrl(doc)).isEqualTo(NoticeHtmlExtractor.posterUrl(doc));
    }

    @Test
    void resolvesRelativeOgImageOnlyWhenItIsAPoster() {
        var doc = Jsoup.parse("<meta property='og:image' content='../poster.png'>", "https://a.example/board/view");
        assertThat(PosterImageSelector.find(doc)).isEqualTo("https://a.example/poster.png");
        assertThat(PosterImageSelector.find(Jsoup.parse("<meta property='og:image' content='/UI_Mark_view.png'>",
                "https://a.example/"))).isNull();
    }

    @Test
    void skipsChromeSmallIconsAndUnsafeUrlsAndUsesLazyPoster() {
        var doc = Jsoup.parse("""
                <main><nav><img src='/2026.jpg'></nav><img src='/a.png' width='20'>
                <img src='data:image/png;base64,xxx'><img src='/blank.gif' data-src='/file?id=123'></main>
                """, "https://a.example/");
        assertThat(PosterImageSelector.find(doc)).isEqualTo("https://a.example/file?id=123");
    }

    @Test
    void usesImageAttachmentButDoesNotPickUnrelatedWholePageImage() {
        var doc = Jsoup.parse("<article><a href='/download?id=123'>모집포스터.jpg</a></article>", "https://a.example/");
        assertThat(PosterImageSelector.find(doc)).isEqualTo("https://a.example/download?id=123");
        assertThat(PosterImageSelector.find(Jsoup.parse(
                "<body><img src='/random.jpg'></body>", "https://a.example/"))).isNull();
    }
}
