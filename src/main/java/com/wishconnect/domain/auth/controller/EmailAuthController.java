package com.wishconnect.domain.auth.controller;

import com.wishconnect.domain.auth.dto.request.EmailVerifyRequest;
import com.wishconnect.domain.auth.dto.request.SendVerificationCodeRequest;
import com.wishconnect.domain.auth.dto.response.EmailCheckResponse;
import com.wishconnect.domain.auth.dto.response.EmailVerifyResponse;
import com.wishconnect.domain.auth.dto.response.VerificationCodeResponse;
import com.wishconnect.domain.auth.service.EmailVerificationService;
import com.wishconnect.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/email")
@RequiredArgsConstructor
@Validated
public class EmailAuthController {

	private final EmailVerificationService emailVerificationService;

	/** 이메일 중복 확인 (LOCAL 기준). */
	@GetMapping("/check")
	public ApiResponse<EmailCheckResponse> checkEmail(@RequestParam @NotBlank @Email String email) {
		boolean available = emailVerificationService.isEmailAvailable(email);
		return ApiResponse.ok(new EmailCheckResponse(available));
	}

	/** 6자리 인증 코드 발송. */
	@PostMapping("/verification-code")
	public ApiResponse<VerificationCodeResponse> sendVerificationCode(
			@Valid @RequestBody SendVerificationCodeRequest request) {
		long expiresIn = emailVerificationService.sendCode(request.email());
		return ApiResponse.ok(new VerificationCodeResponse(true, expiresIn));
	}

	/** 인증 코드 확인. */
	@PostMapping("/verify")
	public ApiResponse<EmailVerifyResponse> verify(@Valid @RequestBody EmailVerifyRequest request) {
		emailVerificationService.verifyCode(request.email(), request.code());
		return ApiResponse.ok(new EmailVerifyResponse(true));
	}
}
