package com.wishconnect.domain.scholarship.dto;

/**
 * LLM 조건 구조화 추출 실행 결과.
 */
public record ConditionExtractionResponse(
		int targetCount,
		int extractedCount,
		int skippedCount
) {
}
