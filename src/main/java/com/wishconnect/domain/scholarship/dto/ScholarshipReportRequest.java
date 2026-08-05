package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.ReportReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 사용자 오등록 신고 요청. */
public record ScholarshipReportRequest(
		@Schema(description = "신고 사유", example = "WRONG_DEADLINE")
		@NotNull ReportReason reason,

		@Schema(description = "상세 내용. reason 이 OTHER 면 사실상 필수")
		@Size(max = 1000) String detail
) {
}
