package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 중복 후보 일괄 "중복 아님"(반려) 요청. 같은 사유를 선택한 후보 전부에 적용한다.
 *
 * <p>단건 반려와 달리 사유를 쿼리 파라미터로 받지 않는다(접근 로그에 사유가 남는다). 건수(1~50)·사유(필수,
 * 공백 불가, 500자 이하)는 서비스에서 전용 오류 코드로 검사한다.
 */
@Schema(description = "중복 후보 일괄 반려 요청")
public record MergeBulkRejectRequest(
		@Schema(description = "반려할 후보 ID. 요청 순서대로 처리한다(1~50건)", example = "[12, 15]")
		List<Long> candidateIds,
		@Schema(description = "중복이 아닌 사유. 선택한 후보 전부에 같은 사유가 남는다(필수, 500자 이하)",
				example = "캠퍼스가 다른 별개 모집")
		String note
) {
}
