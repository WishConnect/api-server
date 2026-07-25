package com.wishconnect.domain.auth.controller;

import com.wishconnect.domain.auth.dto.request.PasswordResetCodeRequest;
import com.wishconnect.domain.auth.dto.request.PasswordResetRequest;
import com.wishconnect.domain.auth.dto.response.PasswordResetResponse;
import com.wishconnect.domain.auth.dto.response.VerificationCodeResponse;
import com.wishconnect.domain.auth.service.PasswordResetService;
import com.wishconnect.global.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "인증 - 비밀번호", description = "비밀번호 재설정 요청·확정")
@RestController
@RequestMapping("/api/v1/auth/password")
@RequiredArgsConstructor
public class PasswordController {

	private final PasswordResetService passwordResetService;

	/** 비밀번호 재설정 코드 발송 (LOCAL 전용, 응답은 항상 동일). */
	@PostMapping("/reset-request")
	public ApiResponse<VerificationCodeResponse> resetRequest(
			@Valid @RequestBody PasswordResetCodeRequest request) {
		long expiresIn = passwordResetService.requestReset(request.email());
		return ApiResponse.ok(new VerificationCodeResponse(true, expiresIn));
	}

	/** 새 비밀번호로 변경. */
	@PostMapping("/reset")
	public ApiResponse<PasswordResetResponse> reset(@Valid @RequestBody PasswordResetRequest request) {
		passwordResetService.resetPassword(request.email(), request.code(), request.newPassword());
		return ApiResponse.ok(new PasswordResetResponse(true));
	}
}
