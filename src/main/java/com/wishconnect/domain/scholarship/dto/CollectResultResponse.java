package com.wishconnect.domain.scholarship.dto;

/**
 * 크롤링 수집기 실행 결과.
 *
 * @param error 이 출처 수집이 통째로 실패했을 때의 원인. 성공이면 null.
 *              일괄 수집이 대학 단위로 예외를 삼키므로, 실패를 배치 이력에 남기려면 여기 실어야 한다
 */
public record CollectResultResponse(
		String source,
		int fetchedCount,
		int savedCount,
		int skippedCount,
		String error
) {

	public CollectResultResponse(String source, int fetchedCount, int savedCount, int skippedCount) {
		this(source, fetchedCount, savedCount, skippedCount, null);
	}

	public static CollectResultResponse failed(String source, Exception e) {
		return new CollectResultResponse(source, 0, 0, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
	}
}
