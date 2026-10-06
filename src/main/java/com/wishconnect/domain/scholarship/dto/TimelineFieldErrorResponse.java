package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 선발 일정 검증 실패 위치. 실패 응답의 {@code data} 로 내려간다(메시지는 ErrorCode 문구).
 *
 * @param index     0부터 센 행 번호. 목록 전체 오류(최대 개수 초과)는 null
 * @param field     요청 필드명. 예: {@code title}, {@code startDate}. 목록 전체 오류는 {@code timeline}
 * @param maxLength 길이·개수 제한 오류일 때 허용 최대값. 그 밖에는 null
 */
@Schema(description = "선발 일정 검증 실패 위치(실패 응답 data)")
public record TimelineFieldErrorResponse(
		@Schema(description = "0부터 센 행 번호. 목록 전체 오류면 null") Integer index,
		@Schema(description = "필드명(title, startDate 등). 목록 전체 오류면 timeline") String field,
		@Schema(description = "길이·개수 제한 오류일 때 최대값") Integer maxLength
) {
}
