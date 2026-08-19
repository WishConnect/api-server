package com.wishconnect.domain.inquiry.dto;

import com.wishconnect.domain.inquiry.entity.ContentInquiryType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ContentInquiryRequest(
		@Schema(description = "문의 유형(선택)", example = "COPYRIGHT_INFRINGEMENT")
		ContentInquiryType inquiryType,

		@Schema(description = "문의 대상 콘텐츠 또는 장학금", example = "OO재단 장학금 포스터")
		@Size(max = 200) String inquiryTarget,

		@Schema(description = "기관명 또는 성명", example = "OO재단 홍길동")
		@Size(max = 100) String organizationName,

		@Schema(description = "회신 이메일", example = "contact@example.com")
		@NotBlank @Email @Size(max = 254) String email,

		@Schema(description = "연락처", example = "010-1234-5678")
		@Pattern(regexp = "^$|^[0-9+() -]{7,30}$", message = "연락처 형식이 올바르지 않습니다.")
		String phone,

		@Schema(description = "문의 내용", example = "해당 포스터의 게시 중단을 요청합니다.")
		@NotBlank @Size(max = 500) String content
) {
}
