package com.wishconnect.domain.scholarship.dto;

import java.util.List;

/**
 * 자동 보완 결과.
 *
 * <p>{@code skippedRows} 는 신뢰할 만한 상세페이지를 못 찾은 건이다. 자동으로 붙이지 않고
 * 남겨두는 게 맞다고 판단한 것들이라, 관리자 화면에서 사람이 처리하면 된다.
 */
public record EnrichmentResult(
		int targetCount,
		int detailUrlFound,
		int imageSaved,
		int documentLinked,
		int skippedCount,
		List<Skipped> skippedRows
) {

	public record Skipped(Long scholarshipId, String title, String reason) {
	}

	public static EnrichmentResult empty() {
		return new EnrichmentResult(0, 0, 0, 0, 0, List.of());
	}
}
