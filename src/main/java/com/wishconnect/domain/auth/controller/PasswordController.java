package com.wishconnect.domain.auth.controller;

import com.wishconnect.domain.auth.dto.request.PasswordResetCodeRequest;
import com.wishconnect.domain.auth.dto.request.PasswordResetRequest;
import com.wishconnect.domain.auth.dto.request.PasswordResetVerifyRequest;
import com.wishconnect.domain.auth.dto.response.PasswordResetResponse;
import com.wishconnect.domain.auth.dto.response.PasswordResetVerifyResponse;
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
		long expiresIn = passwordResetService.requestReset(request.loginId(), request.email());
		return ApiResponse.ok(new VerificationCodeResponse(true, expiresIn));
	}

	/** 아이디·이메일과 인증 코드를 검증하고 일회성 재설정 토큰을 발급한다. */
	@PostMapping("/verify")
	public ApiResponse<PasswordResetVerifyResponse> verify(
			@Valid @RequestBody PasswordResetVerifyRequest request) {
		return ApiResponse.ok(passwordResetService.verifyCode(
				request.loginId(), request.email(), request.code()));
	}

	/** 새 비밀번호로 변경. */
	@PostMapping("/reset")
	public ApiResponse<PasswordResetResponse> reset(@Valid @RequestBody PasswordResetRequest request) {
		passwordResetService.resetPassword(request.resetToken(), request.newPassword());
		return ApiResponse.ok(new PasswordResetResponse(true));
	}
}
