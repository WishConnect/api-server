package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.ReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 관리자 신고 처리 요청. */
public record ReportResolveRequest(
		@Schema(description = "처리 결과", example = "RESOLVED")
		@NotNull ReportStatus status,

		@Schema(description = "처리 메모(반려 사유 등)")
		@Size(max = 1000) String adminNote
) {
}
