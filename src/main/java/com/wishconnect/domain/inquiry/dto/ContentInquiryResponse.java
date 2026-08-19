package com.wishconnect.domain.inquiry.dto;

import com.wishconnect.domain.inquiry.entity.ContentInquiry;
import com.wishconnect.domain.inquiry.entity.ContentInquiryStatus;
import com.wishconnect.domain.inquiry.entity.ContentInquiryType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "콘텐츠 이용 문의 접수·조회·처리 응답")
public record ContentInquiryResponse(
		@Schema(description = "문의 ID", example = "12")
		Long inquiryId,
		@Schema(description = "문의 유형. 선택하지 않았으면 null", example = "COPYRIGHT_INFRINGEMENT")
		ContentInquiryType inquiryType,
		@Schema(description = "문의 대상 콘텐츠 또는 장학금", example = "OO재단 장학금 포스터")
		String inquiryTarget,
		@Schema(description = "문의 기관명 또는 문의자 성명", example = "OO재단 홍길동")
		String organizationName,
		@Schema(description = "회신 이메일", example = "contact@example.com")
		String email,
		@Schema(description = "연락처", example = "010-1234-5678")
		String phone,
		@Schema(description = "문의 내용", example = "해당 포스터의 게시 중단을 요청합니다.")
		String content,
		@Schema(description = "첨부파일 원본 이름. 첨부하지 않았으면 null", example = "권리증빙.pdf")
		String attachmentName,
		@Schema(description = "15분 동안 유효한 첨부파일 다운로드 URL. 첨부하지 않았으면 null")
		String attachmentUrl,
		@Schema(description = "처리 상태", example = "PENDING")
		ContentInquiryStatus status,
		@Schema(description = "관리자 처리 메모. 처리 전이면 null", example = "게시 중단 완료")
		String adminNote,
		@Schema(description = "접수 시각", example = "2026-08-19T15:30:00")
		LocalDateTime createdAt,
		@Schema(description = "처리 완료·반려 시각. 처리 전이면 null", example = "2026-08-20T10:00:00")
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
