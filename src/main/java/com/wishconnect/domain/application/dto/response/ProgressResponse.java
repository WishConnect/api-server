package com.wishconnect.domain.application.dto.response;

/**
 * 지원서 문항 작성 진행률.
 *
 * @param completed 완료 확정된 문항 수
 * @param total     전체 문항 수
 */
public record ProgressResponse(int completed, int total) {
}
