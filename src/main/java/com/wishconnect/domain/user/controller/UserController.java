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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "마이페이지", description = "내 정보 조회 및 계정 관리")
public class UserController {

	private final UserAccountService userAccountService;

	@Operation(summary = "내 정보 조회", description = "마이페이지 첫 화면에 필요한 사용자 요약 정보를 조회합니다.")
	@GetMapping
	public ApiResponse<MyPageResponse> getMyPage(@AuthenticationPrincipal String userId) {
		return ApiResponse.ok(userAccountService.getMyPage(UUID.fromString(userId)));
	}

	@Operation(summary = "비밀번호 변경", description = "현재 비밀번호를 확인한 뒤 새 비밀번호로 변경합니다. 변경 성공 시 기존 Refresh Token을 무효화합니다.")
	@PatchMapping("/password")
	public ApiResponse<UpdateResponse> updatePassword(
			@AuthenticationPrincipal String userId,
			@Valid @RequestBody PasswordUpdateRequest request
	) {
		return ApiResponse.ok(userAccountService.updatePassword(UUID.fromString(userId), request));
	}

	@Operation(summary = "이메일 중복 확인", description = "마이페이지에서 변경하려는 이메일의 사용 가능 여부를 확인합니다.")
	@PostMapping("/email/check")
	public ApiResponse<EmailCheckResponse> checkEmail(@Valid @RequestBody EmailCheckRequest request) {
		return ApiResponse.ok(userAccountService.checkEmail(request));
	}

	@Operation(summary = "이메일 변경 인증코드 발송", description = "변경하려는 이메일 주소로 인증코드를 발송합니다.")
	@PostMapping("/email/verification")
	public ApiResponse<UpdateResponse> sendEmailVerification(
			@AuthenticationPrincipal String userId,
			@Valid @RequestBody EmailVerificationSendRequest request
	) {
		return ApiResponse.ok(userAccountService.sendEmailVerification(UUID.fromString(userId), request));
	}

	@Operation(summary = "이메일 변경 인증코드 확인", description = "변경 이메일로 발송된 인증코드를 확인하고 사용자별 인증 완료 상태를 저장합니다.")
	@PostMapping("/email/verify")
	public ApiResponse<UpdateResponse> verifyEmail(
			@AuthenticationPrincipal String userId,
			@Valid @RequestBody EmailVerificationConfirmRequest request
	) {
		return ApiResponse.ok(userAccountService.verifyEmail(UUID.fromString(userId), request));
	}

	@Operation(summary = "이메일 변경", description = "인증이 완료된 이메일 주소로 로그인 사용자의 이메일을 변경합니다.")
	@PatchMapping("/email")
	public ApiResponse<UpdateResponse> updateEmail(
			@AuthenticationPrincipal String userId,
			@Valid @RequestBody EmailUpdateRequest request
	) {
		return ApiResponse.ok(userAccountService.updateEmail(UUID.fromString(userId), request));
	}

	@Operation(summary = "회원 탈퇴", description = "로그인 사용자를 soft delete 처리하고 Refresh Token을 삭제합니다.")
	@DeleteMapping
	public ApiResponse<Void> deleteMe(@AuthenticationPrincipal String userId) {
		userAccountService.deleteMe(UUID.fromString(userId));
		return ApiResponse.ok();
	}
}
