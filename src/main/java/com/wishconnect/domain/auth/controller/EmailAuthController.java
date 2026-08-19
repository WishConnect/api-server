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
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

@Tag(name = "인증 - 이메일", description = "이메일 중복확인·인증코드 발송·인증 확인")
@RestController
@RequestMapping("/api/v1/auth/email")
@RequiredArgsConstructor
@Validated
public class EmailAuthController {

	private final EmailVerificationService emailVerificationService;

	/** 이메일 중복 확인 (LOCAL 기준). */
	@GetMapping("/check")
	@Operation(summary = "회원가입 이메일 중복 확인", description = "LOCAL 계정으로 가입 가능한 이메일인지 확인합니다.")
	public ApiResponse<EmailCheckResponse> checkEmail(@RequestParam @NotBlank @Email String email) {
		boolean available = emailVerificationService.isEmailAvailable(email);
		return ApiResponse.ok(new EmailCheckResponse(available));
	}

	/** 6자리 인증 코드 발송. */
	@PostMapping("/verification-code")
	@Operation(summary = "회원가입 인증코드 발송", description = "이메일로 6자리 인증코드를 발송하고 만료 시간을 초 단위로 반환합니다.")
	public ApiResponse<VerificationCodeResponse> sendVerificationCode(
			@Valid @RequestBody SendVerificationCodeRequest request) {
		long expiresIn = emailVerificationService.sendCode(request.email());
		return ApiResponse.ok(new VerificationCodeResponse(true, expiresIn));
	}

	/** 인증 코드 확인. */
	@PostMapping("/verify")
	@Operation(summary = "회원가입 이메일 인증", description = "이메일과 인증코드를 확인해 회원가입 전제 조건을 완료합니다.")
	public ApiResponse<EmailVerifyResponse> verify(@Valid @RequestBody EmailVerifyRequest request) {
		emailVerificationService.verifyCode(request.email(), request.code());
		return ApiResponse.ok(new EmailVerifyResponse(true));
	}
}
