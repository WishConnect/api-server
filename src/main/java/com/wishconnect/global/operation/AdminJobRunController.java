package com.wishconnect.global.operation;

import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 - 배치", description = "배치 실행 이력과 실패 알림")
@RestController
@RequestMapping("/api/v1/admin/jobs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminJobRunController {

	private final AdminJobRunService service;

	@GetMapping
	@Operation(summary = "배치 실행 이력 조회", description = "WARNING과 FAILED 상태가 관리자 실패 알림 대상입니다.")
	public ApiResponse<Page<AdminJobRunResponse>> find(
			@RequestParam(required = false) AdminJobStatus status, Pageable pageable) {
		return ApiResponse.ok(service.find(status, pageable));
	}
}
