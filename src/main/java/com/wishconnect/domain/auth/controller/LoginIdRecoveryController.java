package com.wishconnect.domain.auth.controller;

import com.wishconnect.domain.auth.dto.request.LoginIdFindRequest;
import com.wishconnect.domain.auth.dto.request.LoginIdFindVerifyRequest;
import com.wishconnect.domain.auth.dto.response.LoginIdFindResponse;
import com.wishconnect.domain.auth.dto.response.VerificationCodeResponse;
import com.wishconnect.domain.auth.service.LoginIdRecoveryService;
import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증 - 아이디", description = "LOCAL 계정 아이디 찾기")
@RestController
@RequestMapping("/api/v1/auth/login-id")
@RequiredArgsConstructor
public class LoginIdRecoveryController {

	private final LoginIdRecoveryService loginIdRecoveryService;

	@PostMapping("/find-request")
	public ApiResponse<VerificationCodeResponse> findRequest(
			@Valid @RequestBody LoginIdFindRequest request) {
		long expiresIn = loginIdRecoveryService.requestCode(request.email(), request.name());
		return ApiResponse.ok(new VerificationCodeResponse(true, expiresIn));
	}

	@PostMapping("/find")
	public ApiResponse<LoginIdFindResponse> find(
			@Valid @RequestBody LoginIdFindVerifyRequest request) {
		String loginId = loginIdRecoveryService.verifyAndFind(
				request.email(), request.name(), request.code());
		return ApiResponse.ok(new LoginIdFindResponse(loginId));
	}
}
