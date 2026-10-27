package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 중복 후보 일괄 병합 요청.
 *
 * <p>건수(1~50)는 Bean Validation 이 아니라 서비스에서 검사한다. 공통 INVALID_INPUT 대신 전용 오류 코드로
 * 내려 화면이 무엇을 고쳐야 하는지 알 수 있게 하기 위해서다.
 */
@Schema(description = "중복 후보 일괄 병합 요청")
public record MergeBulkApproveRequest(
		@Schema(description = "병합할 후보 ID. 요청 순서대로 처리한다(1~50건)", example = "[12, 15, 18]")
		List<Long> candidateIds
) {
}
