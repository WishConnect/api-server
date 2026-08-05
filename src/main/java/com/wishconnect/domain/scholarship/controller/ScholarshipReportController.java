package com.wishconnect.domain.scholarship.controller;

import com.wishconnect.domain.scholarship.dto.ScholarshipReportRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipReportResponse;
import com.wishconnect.domain.scholarship.service.ScholarshipReportService;
import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장학금 오등록 신고(사용자용).
 *
 * <p>수집이 크롤링·공공데이터 파싱이라 마감일이나 금액이 틀린 채 올라오는 경우가 있다.
 * 실사용자가 발견한 오류를 접수받아 관리자가 고칠 수 있게 한다.
 */
@Tag(name = "장학금 - 오등록 신고", description = "잘못된 장학금 정보 신고 및 내 신고 조회")
@RestController
@RequestMapping("/api/v1/scholarships")
@RequiredArgsConstructor
public class ScholarshipReportController {

	private final ScholarshipReportService scholarshipReportService;

	@Operation(summary = "장학금 오등록 신고",
			description = "같은 장학금에 아직 처리되지 않은 내 신고가 있으면 409 로 막는다.")
	@PostMapping("/{scholarshipId}/reports")
	public ResponseEntity<ApiResponse<ScholarshipReportResponse>> report(
			@AuthenticationPrincipal String userId,
			@PathVariable Long scholarshipId,
			@Valid @RequestBody ScholarshipReportRequest request) {
		ScholarshipReportResponse response =
				scholarshipReportService.report(UUID.fromString(userId), scholarshipId, request);
		return ResponseEntity.status(201).body(ApiResponse.ok(response));
	}

	@Operation(summary = "내가 낸 신고 목록", description = "처리 상태와 관리자 메모를 함께 돌려준다.")
	// 관리자용 /reports/** 와 겹치지 않도록 별도 경로를 쓴다(겹치면 ADMIN 규칙에 걸려 401 이 된다).
	@GetMapping("/my-reports")
	public ApiResponse<List<ScholarshipReportResponse>> myReports(
			@AuthenticationPrincipal String userId,
			Pageable pageable) {
		return ApiResponse.ok(scholarshipReportService.findMine(UUID.fromString(userId), pageable));
	}
}
