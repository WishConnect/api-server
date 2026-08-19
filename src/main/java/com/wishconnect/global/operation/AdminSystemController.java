package com.wishconnect.global.operation;

import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 - 시스템", description = "애플리케이션·DB·Redis·호스트 상태와 마스킹 로그")
@RestController
@RequestMapping("/api/v1/admin/system")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSystemController {

	private final AdminSystemService service;

	@GetMapping("/status")
	@Operation(summary = "운영 시스템 상태 조회")
	public ApiResponse<AdminSystemStatusResponse> status() {
		return ApiResponse.ok(service.status());
	}

	@GetMapping("/logs")
	@Operation(summary = "애플리케이션 로그 tail 조회",
			description = "서버에 설정된 app.log만 읽으며 최대 500줄, 민감정보와 이메일·전화번호를 마스킹합니다.")
	public ApiResponse<AdminLogResponse> logs(
			@RequestParam(defaultValue = "200") Integer lines,
			@RequestParam(required = false) String level,
			@RequestParam(required = false) String keyword) {
		return ApiResponse.ok(service.logs(lines, level, keyword));
	}
}
