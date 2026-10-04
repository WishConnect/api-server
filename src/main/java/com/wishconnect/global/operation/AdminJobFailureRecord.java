package com.wishconnect.global.operation;

/**
 * 배치 단계 안의 개별 실패 한 건(기록 전 값).
 *
 * @param targetType  RAW_SCHOLARSHIP(원문) / SCHOLARSHIP / SOURCE(수집 출처) / GROUP(중복 묶음) / STEP(단계 전체)
 * @param targetId    원문·장학금 ID. 출처·단계면 null
 * @param targetLabel 사람이 알아볼 이름(제목·출처 코드 등)
 */
public record AdminJobFailureRecord(
		String targetType,
		Long targetId,
		String targetLabel,
		AdminJobFailureType failureType,
		String reason
) {
}
