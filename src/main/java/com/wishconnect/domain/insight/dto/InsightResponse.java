package com.wishconnect.domain.insight.dto;

import java.util.List;

public record InsightResponse(
        List<InsightArticleResponse> articles,
        List<String> popularTags,
        PaginationDto pagination
) {
    public record PaginationDto(
            int page,
            int size,
            int totalCount,
            int totalPages
    ) {
    }
}
