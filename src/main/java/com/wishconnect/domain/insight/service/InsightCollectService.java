package com.wishconnect.domain.insight.service;

import com.wishconnect.domain.insight.client.NaverSearchClient;
import com.wishconnect.domain.insight.dto.NaverSearchItem;
import com.wishconnect.domain.insight.dto.NaverSearchResponse;
import com.wishconnect.domain.insight.util.InsightContentCrawler;
import com.wishconnect.domain.insight.dto.InsightSummaryResult;
import com.wishconnect.domain.insight.entity.Insight;
import com.wishconnect.domain.insight.entity.InsightCategory;
import com.wishconnect.domain.insight.entity.InsightSource;
import com.wishconnect.domain.insight.entity.Tag;
import com.wishconnect.domain.insight.entity.InsightTag;
import com.wishconnect.domain.insight.repository.InsightRepository;
import com.wishconnect.domain.insight.repository.InsightCategoryRepository;
import com.wishconnect.domain.insight.repository.TagRepository;
import com.wishconnect.domain.insight.repository.InsightTagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightCollectService {

    private final NaverSearchClient naverSearchClient;
    private final InsightContentCrawler contentCrawler;
    private final InsightSummaryService summaryService;
    private final InsightRepository insightRepository;
    private final InsightCategoryRepository insightCategoryRepository;
    private final TagRepository tagRepository;
    private final InsightTagRepository insightTagRepository;

    @Transactional
    public int collectByKeyword(String keyword) {
        List<NaverSearchItem> items = new ArrayList<>();
        items.addAll(naverSearchClient.searchBlog(keyword, 10).items());

        NaverSearchResponse webResponse = naverSearchClient.searchWeb(keyword, 30);
        if (webResponse != null && webResponse.items() != null) {
            log.info("[Insight] 웹문서 검색 결과 전체 개수={}", webResponse.items().size());

            List<NaverSearchItem> tistoryItems = webResponse.items().stream()
                    .filter(item -> item.link().contains("tistory.com"))
                    .toList();

            log.info("[Insight] 티스토리 필터링 결과 개수={}", tistoryItems.size());

            items.addAll(tistoryItems);
        }

        // 네이버 카페는 봇 차단(HTTP 999)으로 크롤링이 대부분 실패하여
        // 수집 대상에서 제외. 접근 정책이 변경되면 재검토 필요.
        //items.addAll(naverSearchClient.searchCafe(keyword, 5).items());

        int savedCount = 0;

        for (NaverSearchItem item : items) {
            try {
                if (processItem(item)) {
                    savedCount++;
                }
            } catch (Exception e) {
                log.warn("[Insight] 개별 항목 처리 실패, 스킵 link={}", item.link(), e);
            }
        }

        log.info("[Insight] 수집 완료 keyword={} 시도={} 저장={}",
                keyword, items.size(), savedCount);

        return savedCount;
    }

    private boolean processItem(NaverSearchItem item) {
        String cleanUrl = item.link();

        if (insightRepository.existsByOriginalUrl(cleanUrl)) {
            return false;
        }

        String rawContent = contentCrawler.crawl(cleanUrl);
        if (rawContent == null || rawContent.isBlank()) {
            log.warn("[Insight] 본문 크롤링 실패, 스킵 url={}", cleanUrl);
            return false;
        }

        InsightSummaryResult summary = summaryService.summarize(
                item.getCleanTitle(), rawContent
        );

        InsightCategory category = resolveCategory(summary.category());
        InsightSource source = detectSource(cleanUrl);

        Insight insight = Insight.builder()
                .category(category)
                .title(summary.title())          // LLM이 다듬은 제목 사용
                .content(summary.summary())
                .originalUrl(cleanUrl)
                .viewCount(0)
                .publishedAt(parsePublishedAt(item.postdate()))
                .source(source)
                .build();

        insightRepository.save(insight);

        saveTags(insight, summary.tags());

        return true;
    }

    private InsightCategory resolveCategory(String categoryCode) {
        return insightCategoryRepository.findByName(categoryCode)
                .orElseGet(() -> {
                    log.warn("[Insight] 알 수 없는 카테고리, 기본값 사용 category={}", categoryCode);
                    return insightCategoryRepository.findByName("SCHOLARSHIP_INFO")
                            .orElseThrow();
                });
    }

    private InsightSource detectSource(String url) {
        if (url.contains("blog.naver.com")) return InsightSource.NAVER_BLOG;
        if (url.contains("cafe.naver.com")) return InsightSource.NAVER_CAFE;
        if (url.contains("tistory.com")) return InsightSource.TISTORY;
        throw new IllegalArgumentException("알 수 없는 출처: " + url);
    }

    private LocalDateTime parsePublishedAt(String postdate) {
        if (postdate == null || postdate.isBlank()) {
            return LocalDateTime.now();  // 카페글은 postdate가 없으니 현재 시각으로 대체
        }

        try {
            return LocalDateTime.parse(
                    postdate + "0000",
                    DateTimeFormatter.ofPattern("yyyyMMddHHmm")
            );
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private void saveTags(Insight insight, List<String> tagNames) {
        if (tagNames == null) return;

        for (String tagName : tagNames) {
            Tag tag = tagRepository.findByName(tagName)
                    .orElseGet(() -> tagRepository.save(
                            Tag.builder().name(tagName).build()
                    ));

            InsightTag insightTag = InsightTag.builder()
                    .insight(insight)
                    .tag(tag)
                    .build();

            insightTagRepository.save(insightTag);
        }
    }
}
