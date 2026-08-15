package com.wishconnect.domain.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverSearchItem(
        String title,
        String link,
        String description,
        String bloggername,
        String postdate
) {
    // <b> 태그 제거한 순수 텍스트 제목
    public String getCleanTitle() {
        return title.replaceAll("<[^>]*>", "");
    }
}