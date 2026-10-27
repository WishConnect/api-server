package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.RawDismissReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 실패 재처리 원본 만료 처리 요청. 건수(1~100)·사유·메모는 서비스에서 전용 오류 코드로 검사한다.
 *
 * @param closeLinkedScholarship true 면 연결된 장학금도 마감(CLOSED)으로 바꾼다. 삭제하지 않는다. 생략하면 false
 */
@Schema(description = "실패 원본 만료 처리 요청")
public record RawDismissRequest(
		@Schema(description = "만료 처리할 원본 ID(1~100건)", example = "[8766, 8770]") List<Long> rawIds,
		@Schema(description = "사유. 필수", example = "PERIOD_EXPIRED") RawDismissReason reason,
		@Schema(description = "메모. 사유가 OTHER 면 필수, 200자 이하", example = "2025년 공고") String note,
		@Schema(description = "연결된 장학금도 마감 처리", example = "false") Boolean closeLinkedScholarship
) {
}
