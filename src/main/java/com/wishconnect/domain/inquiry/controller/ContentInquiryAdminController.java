package com.wishconnect.domain.inquiry.controller;

import com.wishconnect.domain.inquiry.dto.ContentInquiryResolveRequest;
import com.wishconnect.domain.inquiry.dto.ContentInquiryResponse;
import com.wishconnect.domain.inquiry.entity.ContentInquiryStatus;
import com.wishconnect.domain.inquiry.entity.ContentInquiryType;
import com.wishconnect.domain.inquiry.service.ContentInquiryService;
import com.wishconnect.global.audit.AdminAction;
import com.wishconnect.global.audit.AdminAuditLogService;
import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 - 콘텐츠 이용 문의", description = "콘텐츠 문의 목록 및 처리")
@RestController
@RequestMapping("/api/v1/admin/content-inquiries")
@RequiredArgsConstructor
public class ContentInquiryAdminController {

	private final ContentInquiryService contentInquiryService;
	private final AdminAuditLogService adminAuditLogService;

	@GetMapping
	@Operation(summary = "콘텐츠 이용 문의 목록", description = "상태 미지정 시 전체를 최신순으로 반환합니다. ADMIN 전용입니다.")
	public ApiResponse<Page<ContentInquiryResponse>> findAll(
			@Parameter(description = "처리 상태 필터: PENDING, RESOLVED, REJECTED")
			@RequestParam(required = false) ContentInquiryStatus status,
			@RequestParam(required = false) ContentInquiryType type,
			@RequestParam(required = false) String keyword,
			Pageable pageable) {
		return ApiResponse.ok(contentInquiryService.findAll(status, type, keyword, pageable));
	}

	@PatchMapping("/{inquiryId}")
	@Operation(summary = "콘텐츠 이용 문의 처리", description = "처리 상태와 관리자 메모를 변경합니다. ADMIN 전용입니다.")
	public ApiResponse<ContentInquiryResponse> resolve(
			@Parameter(hidden = true)
			@AuthenticationPrincipal String actorId,
			@Parameter(description = "처리할 문의 ID", example = "12")
			@PathVariable Long inquiryId,
			@Valid @RequestBody ContentInquiryResolveRequest request) {
		ContentInquiryResponse response = contentInquiryService.resolve(inquiryId, request);
		adminAuditLogService.record(UUID.fromString(actorId), AdminAction.CONTENT_INQUIRY_RESOLVE,
				"CONTENT_INQUIRY", inquiryId, String.valueOf(request.status()));
		return ApiResponse.ok(response);
	}
}
