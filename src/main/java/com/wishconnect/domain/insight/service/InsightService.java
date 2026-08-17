package com.wishconnect.domain.insight.service;

import com.wishconnect.domain.insight.dto.InsightArticleResponse;
import com.wishconnect.domain.insight.dto.InsightResponse;
import com.wishconnect.domain.insight.entity.Insight;
import com.wishconnect.domain.insight.entity.InsightCategoryCode;
import com.wishconnect.domain.insight.entity.InsightSource;
import com.wishconnect.domain.insight.repository.InsightRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InsightService {

    private final InsightRepository insightRepository;

    public InsightResponse getInsights(
            String category, String source, String sort,
            String tag, String keyword, int page, int size
    ) {
        // 1. 카테고리/소스 유효성 검사
        String categoryName = validateAndResolveCategory(category);
        InsightSource sourceEnum = validateAndResolveSource(source);

        // 2. 조회 (태그 유무로 분기)
        Page<Insight> insightPage;
        if (tag != null && !tag.isBlank()) {
            Pageable pageableWithoutSort = PageRequest.of(page - 1, size);
            insightPage = insightRepository.findAllByTag(tag, categoryName, sourceEnum, pageableWithoutSort);
        } else if (keyword != null && !keyword.isBlank()) {
            Pageable pageable = createPageable(page - 1, size, sort);
            insightPage = insightRepository.searchWithFilter(categoryName, sourceEnum, keyword, pageable);
        } else {
            Pageable pageable = createPageable(page - 1, size, sort);
            insightPage = insightRepository.findAllWithFilter(categoryName, sourceEnum, pageable);
        }

        // 3. 태그 정보 일괄 조회 (N+1 방지)
        List<Long> insightIds = insightPage.getContent().stream()
                .map(Insight::getId)
                .toList();
        Map<Long, List<String>> tagsByInsightId = getTagsByInsightIds(insightIds);

        // 4. DTO 변환
        List<InsightArticleResponse> articles = insightPage.getContent().stream()
                .map(i -> toArticleResponse(i, tagsByInsightId.getOrDefault(i.getId(), List.of())))
                .toList();

        // 5. 페이지네이션
        InsightResponse.PaginationDto pagination = new InsightResponse.PaginationDto(
                page, size,
                (int) insightPage.getTotalElements(),
                insightPage.getTotalPages()
        );

        // 6. 인기 태그 조회 (필터와 무관하게 항상 전체 기준)
        List<String> popularTags = getPopularTags();

        return new InsightResponse(articles, popularTags, pagination);
    }

    private String validateAndResolveCategory(String category) {
        if (category == null || "ALL".equals(category)) {
            return null;
        }
        // 유효한 카테고리인지 검증
        InsightCategoryCode.from(category);
        return category;
    }

    private InsightSource validateAndResolveSource(String source) {
        if (source == null || "ALL".equals(source)) {
            return null;
        }
        try {
            return InsightSource.valueOf(source);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
    }

    private Pageable createPageable(int page, int size, String sort) {
        Sort sortOrder = "popular".equals(sort)
                ? Sort.by(Sort.Direction.DESC, "viewCount")
                : Sort.by(Sort.Direction.DESC, "publishedAt");
        return PageRequest.of(page, size, sortOrder);
    }

    private Map<Long, List<String>> getTagsByInsightIds(List<Long> insightIds) {
        if (insightIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = insightRepository.findTagsByInsightIds(insightIds);
        return rows.stream().collect(Collectors.groupingBy(
                row -> (Long) row[0],
                Collectors.mapping(row -> (String) row[1], Collectors.toList())
        ));
    }

    private InsightArticleResponse toArticleResponse(Insight insight, List<String> tags) {
        InsightCategoryCode categoryCode = InsightCategoryCode.from(insight.getCategory().getName());

        return new InsightArticleResponse(
                insight.getId(),
                categoryCode.name(),
                categoryCode.getLabel(),
                insight.getSource().getLabel(),
                insight.getPublishedAt() != null ? insight.getPublishedAt().toLocalDate() : null,
                insight.getTitle(),
                insight.getContent(),
                insight.getOriginalUrl(),
                tags.stream().map(t -> "#" + t).toList()
        );
    }

    private List<String> getPopularTags() {
        return insightRepository.findPopularTagNames(PageRequest.of(0, 6))
                .stream()
                .map(t -> "#" + t)
                .toList();
    }

}