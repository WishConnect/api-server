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
		/**
		 * 검색 API 자체를 못 써서 중단됐는지. true 면 "매칭 실패" 가 아니라 <b>키·쿼터 문제</b>다.
		 * 이 둘을 구분하지 않아, 키가 401 로 죽어 있는데도 매칭 실패처럼 보인 적이 있다.
		 */
		boolean searchUnavailable,
		List<Skipped> skippedRows
) {

	public record Skipped(Long scholarshipId, String title, String reason) {
	}

	public static EnrichmentResult empty() {
		return new EnrichmentResult(0, 0, 0, 0, 0, false, List.of());
	}
}
