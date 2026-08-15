package com.wishconnect.domain.insight.dto;

import java.time.LocalDate;
import java.util.List;

public record InsightArticleResponse(
        Long insightId,
        String category,
        String categoryLabel,
        String source,
        LocalDate publishedAt,
        String title,
        String summary,
        String originalUrl,
        List<String> tags
) {
}
