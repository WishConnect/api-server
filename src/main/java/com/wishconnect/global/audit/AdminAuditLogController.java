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
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
	/** 페이지 목록 한 쪽 최대 건수. */
	private static final int MAX_PAGE_SIZE = 100;

	private final AdminAuditLogService adminAuditLogService;
	private final AdminAuditRestoreService adminAuditRestoreService;
	private final AdminAuditLogViewAssembler adminAuditLogViewAssembler;

	@Operation(summary = "감사 로그 조회(작업 기록)",
			description = "관리자 쓰기 작업을 최신순으로 준다. 행마다 관리자 이름(actorName, 없으면 로그인 아이디)과 "
					+ "대상·요약에 적힌 장학금의 지금 제목(scholarships)을 함께 준다. 이메일·연락처는 주지 않는다. (ADMIN 전용)")
	@GetMapping("/audit-log")
	public ApiResponse<List<AdminAuditLogResponse>> auditLog(
			@RequestParam(required = false) AdminAction action,
			@RequestParam(defaultValue = "50") int size) {
		int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
		return ApiResponse.ok(adminAuditLogViewAssembler.assemble(
				adminAuditLogService.find(action, PageRequest.of(0, safeSize)).getContent()));
	}

	@Operation(summary = "작업 기록 목록(페이지·필터)",
			description = """
					관리자 쓰기 작업을 최신순으로 페이지 단위로 준다(size 1~100, 기본 50). 응답은 Spring Page 모양이다.
					필터(모두 선택): action, actorId(관리자 UUID), targetType(SCHOLARSHIP·RAW_SCHOLARSHIP·REPORT·CONTENT_INQUIRY·ADMIN_LOGIN),
					targetId, from·to(한국 날짜 yyyy-MM-dd, 둘 다 포함). 행마다 actorName·scholarships 가 붙는다. (ADMIN 전용)
					""")
	@GetMapping("/audit-log/page")
	public ApiResponse<org.springframework.data.domain.Page<AdminAuditLogResponse>> auditLogPage(
			@RequestParam(required = false) AdminAction action,
			@RequestParam(required = false) UUID actorId,
			@RequestParam(required = false) String targetType,
			@RequestParam(required = false) Long targetId,
			@RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
					iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
			@RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
					iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
		org.springframework.data.domain.Page<AdminAuditLog> logs = adminAuditLogService.search(
				new AdminAuditLogSearch(action, actorId, targetType, targetId, from, to), pageable);
		return ApiResponse.ok(new org.springframework.data.domain.PageImpl<>(
				adminAuditLogViewAssembler.assemble(logs.getContent()), logs.getPageable(), logs.getTotalElements()));
	}

	@Operation(summary = "작업 기록의 관리자 목록", description = "관리자 필터용. 기록에 나온 관리자의 ID 와 이름(없으면 로그인 아이디). (ADMIN 전용)")
	@GetMapping("/audit-log/actors")
	public ApiResponse<List<AdminAuditLogViewAssembler.Actor>> auditLogActors() {
		return ApiResponse.ok(adminAuditLogViewAssembler.actors(adminAuditLogService.actorIds()));
	}

	@Operation(summary = "감사 로그 복구 미리보기",
			description = """
					기록 직전 값(recordedValue)·기록 직후 값(loggedValue)·현재 값(currentValue)을 필드별로 비교합니다.
					changedSinceRecord 가 true 면 기록 이후 다른 변경(다른 관리자 수정, 배치 마감 처리 등)이 있었다는
					뜻이고, 그 필드를 복구하면 그 변경을 덮어씁니다. defaultSelected 는 화면 체크박스 기본값이며
					모집 상태(recruitmentStatus)는 항상 false 입니다. (ADMIN 전용)
					""")
	@GetMapping("/audit-log/{logId}/restore-preview")
	public ApiResponse<AuditRestorePreviewResponse> restorePreview(@PathVariable Long logId) {
		return ApiResponse.ok(adminAuditRestoreService.preview(logId));
	}

	@Operation(summary = "감사 로그 필드 선택 복구",
			description = """
					fields 에 고른 필드만 기록 직전 값으로 되돌립니다. 복구 자체가 AUDIT_RESTORE 감사 기록으로 남고,
					그 기록으로 복구를 다시 되돌릴 수 있습니다. 한 기록은 한 번만 복구할 수 있습니다. (ADMIN 전용)
					""")
	@PostMapping("/audit-log/{logId}/restore")
	public ApiResponse<AuditRestoreResultResponse> restoreFields(
			@AuthenticationPrincipal String actorId,
			@PathVariable Long logId,
			@Valid @RequestBody AuditRestoreRequest request) {
		return ApiResponse.ok(adminAuditRestoreService.restore(
				logId, UUID.fromString(actorId), request.fields(), request.reason()));
	}

	@Operation(summary = "감사로그 이전 값 복구 (예전 콘솔 호환)",
			description = """
					예전 콘솔 버튼용. 이제 스냅샷 전체를 덮어쓰지 않고 미리보기의 기본 선택 필드만 되돌립니다
					(모집 상태와 기록 이후 다시 바뀐 필드는 제외). 새 콘솔은 POST /audit-log/{logId}/restore 를 씁니다.
					""")
	@PatchMapping("/audit-log/{logId}/restore")
	public ApiResponse<AuditRestoreResultResponse> restore(
			@AuthenticationPrincipal String actorId,
			@PathVariable Long logId) {
		return ApiResponse.ok(adminAuditRestoreService.restore(
				logId, UUID.fromString(actorId), null, "예전 콘솔 복구 버튼(기본 선택 필드만)"));
	}
}
