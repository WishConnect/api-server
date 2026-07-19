package com.wishconnect.domain.scholarship.dto;

/**
 * 크롤링 수집기 실행 결과.
 */
public record CollectResultResponse(
		String source,
		int fetchedCount,
		int savedCount,
		int skippedCount
) {
}
