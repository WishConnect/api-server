package com.wishconnect.domain.inquiry.dto;

import com.wishconnect.domain.inquiry.entity.ContentInquiryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "관리자 콘텐츠 문의 처리 요청")
public record ContentInquiryResolveRequest(
		@Schema(description = "변경할 처리 상태", example = "RESOLVED")
		@NotNull ContentInquiryStatus status,
		@Schema(description = "처리 내용 또는 반려 사유. 최대 1000자", example = "요청하신 포스터를 게시 중단했습니다.")
		@Size(max = 1000) String adminNote
) {
}
