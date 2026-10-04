package com.wishconnect.global.operation;

import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
	@Operation(summary = "배치 실행 이력 조회",
			description = "PARTIAL_FAILURE(부분 실패)·FAILED 가 관리자 알림 대상입니다. WARNING 은 예전 부분 실패 표시입니다.")
	public ApiResponse<Page<AdminJobRunResponse>> find(
			@RequestParam(required = false) AdminJobStatus status, Pageable pageable) {
		return ApiResponse.ok(service.find(status, pageable));
	}

	@GetMapping("/alerts")
	@Operation(summary = "배치 부분 실패·실패 요약 (알림함·대시보드)",
			description = """
					latest: 가장 최근 실행 1건(상태 무관). attention: 최근 부분 실패·실패 실행 limit 건.
					각 실행마다 단계별·유형별 실패 건수와 llmCreditExhausted(크레딧 부족)·llmAuthFailed(키 오류)
					표시가 있습니다. 두 표시는 재처리로 풀리지 않으니 결제·키를 먼저 확인해야 합니다.
					""")
	public ApiResponse<AdminJobAlertSummaryResponse> alerts(@RequestParam(defaultValue = "10") int limit) {
		return ApiResponse.ok(service.alertSummary(limit));
	}

	@GetMapping("/{runId}/failures")
	@Operation(summary = "배치 실패 상세 목록",
			description = "어느 단계에서 어떤 원문(rawId)·장학금·출처가 왜 실패했는지. step·failureType 으로 거를 수 있습니다.")
	public ApiResponse<Page<AdminJobFailureResponse>> failures(
			@PathVariable Long runId,
			@RequestParam(required = false) String step,
			@RequestParam(required = false) AdminJobFailureType failureType,
			Pageable pageable) {
		return ApiResponse.ok(service.failures(runId, step, failureType, pageable));
	}
}
