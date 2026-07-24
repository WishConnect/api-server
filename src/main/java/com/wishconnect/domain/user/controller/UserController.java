package com.wishconnect.domain.user.controller;

import com.wishconnect.domain.user.dto.request.EmailCheckRequest;
import com.wishconnect.domain.user.dto.request.EmailUpdateRequest;
import com.wishconnect.domain.user.dto.request.EmailVerificationConfirmRequest;
import com.wishconnect.domain.user.dto.request.EmailVerificationSendRequest;
import com.wishconnect.domain.user.dto.request.PasswordUpdateRequest;
import com.wishconnect.domain.user.dto.response.EmailCheckResponse;
import com.wishconnect.domain.user.dto.response.MyPageResponse;
import com.wishconnect.domain.user.dto.response.UpdateResponse;
import com.wishconnect.domain.user.service.UserAccountService;
import com.wishconnect.global.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
마이페이지의 내 정보 요약과 계정 관리 API입니다.
프로필/추천 기준 수정은 /api/v1/users/me/profile 하위 API를 사용합니다.
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {

	private final UserAccountService userAccountService;

	@GetMapping
	public ApiResponse<MyPageResponse> getMyPage(@AuthenticationPrincipal String userId) {
		return ApiResponse.ok(userAccountService.getMyPage(UUID.fromString(userId)));
	}

	@PatchMapping("/password")
	public ApiResponse<UpdateResponse> updatePassword(
			@AuthenticationPrincipal String userId,
			@Valid @RequestBody PasswordUpdateRequest request
	) {
		return ApiResponse.ok(userAccountService.updatePassword(UUID.fromString(userId), request));
	}

	@PostMapping("/email/check")
	public ApiResponse<EmailCheckResponse> checkEmail(@Valid @RequestBody EmailCheckRequest request) {
		return ApiResponse.ok(userAccountService.checkEmail(request));
	}

	@PostMapping("/email/verification")
	public ApiResponse<UpdateResponse> sendEmailVerification(
			@Valid @RequestBody EmailVerificationSendRequest request
	) {
		return ApiResponse.ok(userAccountService.sendEmailVerification(request));
	}

	@PostMapping("/email/verify")
	public ApiResponse<UpdateResponse> verifyEmail(
			@Valid @RequestBody EmailVerificationConfirmRequest request
	) {
		return ApiResponse.ok(userAccountService.verifyEmail(request));
	}

	@PatchMapping("/email")
	public ApiResponse<UpdateResponse> updateEmail(
			@AuthenticationPrincipal String userId,
			@Valid @RequestBody EmailUpdateRequest request
	) {
		return ApiResponse.ok(userAccountService.updateEmail(UUID.fromString(userId), request));
	}

	@DeleteMapping
	public ApiResponse<Void> deleteMe(@AuthenticationPrincipal String userId) {
		userAccountService.deleteMe(UUID.fromString(userId));
		return ApiResponse.ok();
	}
}
