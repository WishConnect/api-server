package com.wishconnect.domain.insight.entity;

public enum InsightSource {
    NAVER_BLOG("네이버 블로그"),
    TISTORY("티스토리"),
    NAVER_CAFE("네이버 카페");

    private final String label;

    InsightSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}