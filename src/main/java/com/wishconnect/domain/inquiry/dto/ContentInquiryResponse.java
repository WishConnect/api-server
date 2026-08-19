package com.wishconnect.domain.inquiry.dto;

import com.wishconnect.domain.inquiry.entity.ContentInquiry;
import com.wishconnect.domain.inquiry.entity.ContentInquiryStatus;
import com.wishconnect.domain.inquiry.entity.ContentInquiryType;
import java.time.LocalDateTime;

public record ContentInquiryResponse(
		Long inquiryId,
		ContentInquiryType inquiryType,
		String inquiryTarget,
		String organizationName,
		String email,
		String phone,
		String content,
		String attachmentName,
		String attachmentUrl,
		ContentInquiryStatus status,
		String adminNote,
		LocalDateTime createdAt,
		LocalDateTime resolvedAt
) {
	public static ContentInquiryResponse from(ContentInquiry inquiry, String attachmentUrl) {
		return new ContentInquiryResponse(
				inquiry.getId(), inquiry.getInquiryType(), inquiry.getInquiryTarget(),
				inquiry.getOrganizationName(), inquiry.getEmail(), inquiry.getPhone(),
				inquiry.getContent(), inquiry.getAttachmentName(), attachmentUrl,
				inquiry.getStatus(), inquiry.getAdminNote(), inquiry.getCreatedAt(), inquiry.getResolvedAt());
	}
}
