package com.wishconnect.domain.insight.dto;

import java.util.List;

public record InsightSummaryResult(
        String title,
        String summary,
        String category,
        List<String> tags
) {
}