package com.wishconnect.domain.search.dto;

import java.util.List;

public record PopularKeywordResponse(
        List<String> keywords
) {
}