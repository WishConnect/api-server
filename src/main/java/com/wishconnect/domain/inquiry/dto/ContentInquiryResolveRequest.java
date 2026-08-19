package com.wishconnect.domain.inquiry.dto;

import com.wishconnect.domain.inquiry.entity.ContentInquiryStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ContentInquiryResolveRequest(
		@NotNull ContentInquiryStatus status,
		@Size(max = 1000) String adminNote
) {
}
