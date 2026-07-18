package com.wishconnect.domain.application.dto.response;

import java.util.List;

/**
 * 지원서 목록 API(①) 응답 래퍼.
 */
public record ApplicationListResponse(
		List<ApplicationListItemResponse> content,
		long totalElements
) {
}
