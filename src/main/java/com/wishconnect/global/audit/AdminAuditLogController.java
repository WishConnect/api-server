package com.wishconnect.global.audit;

import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * 감사 로그 조회. psql 로만 볼 수 있으면 팀원들이 쓸 수 없어 화면에서 보이게 한다.
 */
@Tag(name = "관리 - 감사 로그", description = "관리자 쓰기 작업 이력 (ADMIN 전용)")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditLogController {

	/** 화면에서 훑는 용도라 한 번에 많이 줄 이유가 없다. */
	private static final int MAX_SIZE = 200;

	private final AdminAuditLogService adminAuditLogService;
	private final AdminAuditRestoreService adminAuditRestoreService;

	@Operation(summary = "감사 로그 조회", description = "관리자 쓰기 작업을 최신순으로 준다. (ADMIN 전용)")
	@GetMapping("/audit-log")
	public ApiResponse<List<AdminAuditLogResponse>> auditLog(
			@RequestParam(required = false) AdminAction action,
			@RequestParam(defaultValue = "50") int size) {
		int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
		return ApiResponse.ok(adminAuditLogService.find(action, PageRequest.of(0, safeSize))
				.map(AdminAuditLogResponse::from)
				.getContent());
	}

	@Operation(summary = "감사로그 이전 값 복구",
			description = "수기 수정·내리기 로그의 before 값을 복구합니다. 한 로그는 한 번만 복구할 수 있습니다.")
	@PatchMapping("/audit-log/{logId}/restore")
	public ApiResponse<Void> restore(
			@AuthenticationPrincipal String actorId,
			@PathVariable Long logId) {
		adminAuditRestoreService.restore(logId, UUID.fromString(actorId));
		return ApiResponse.ok();
	}
}
