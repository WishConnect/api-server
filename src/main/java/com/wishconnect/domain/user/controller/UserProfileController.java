package com.wishconnect.domain.user.controller;

import com.wishconnect.domain.user.dto.request.ProfileAcademicRequest;
import com.wishconnect.domain.user.dto.request.ProfileBasicRequest;
import com.wishconnect.domain.user.dto.request.ProfileHouseholdRequest;
import com.wishconnect.domain.user.dto.response.OnboardingCompleteResponse;
import com.wishconnect.domain.user.dto.response.OnboardingStepResponse;
import com.wishconnect.domain.user.dto.response.ProfileResponse;
import com.wishconnect.domain.user.service.UserProfileService;
import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
온보딩에서 입력하는 내 프로필 API입니다.
온보딩 데이터는 결국 사용자 매칭 정보이므로 별도 onboarding 도메인이 아니라 user 도메인에서 관리합니다.
 */
@RestController
@RequestMapping("/api/v1/users/me/profile")
@RequiredArgsConstructor
@Tag(name = "사용자 프로필", description = "온보딩 및 마이페이지 프로필/추천 기준 조회·수정")
public class UserProfileController {

	private final UserProfileService userProfileService;

	@Operation(summary = "프로필 상세 조회", description = "내 프로필 전체를 조회합니다. 온보딩 재진입, 프로필 수정, 추천 기준 수정 화면에서 사용합니다.")
	@GetMapping
	public ApiResponse<ProfileResponse> getProfile(@AuthenticationPrincipal String userId) {
		return ApiResponse.ok(userProfileService.getProfile(UUID.fromString(userId)));
	}

	// STEP 1: 이름, 생년, 연락처, 성별, 국적, 거주지역 저장
	@Operation(summary = "프로필 기본 정보 저장/수정", description = "온보딩 STEP 1 또는 마이페이지 프로필 수정에서 이름, 생년, 연락처, 성별, 국적, 거주지역을 저장합니다.")
	@PutMapping("/basic")
	public ApiResponse<OnboardingStepResponse> saveBasic(
			@AuthenticationPrincipal String userId,
			@Valid @RequestBody ProfileBasicRequest request
	) {
		return ApiResponse.ok(userProfileService.saveBasic(UUID.fromString(userId), request));
	}

	// STEP 2: 학교, 전공, 재학상태, 학년, 학점 정보 저장
	@Operation(summary = "학적 정보 저장/수정", description = "온보딩 STEP 2 또는 추천 기준 수정에서 학교, 전공, 재학상태, 학년, 학점 정보를 저장합니다.")
	@PutMapping("/academic")
	public ApiResponse<OnboardingStepResponse> saveAcademic(
			@AuthenticationPrincipal String userId,
			@Valid @RequestBody ProfileAcademicRequest request
	) {
		return ApiResponse.ok(userProfileService.saveAcademic(UUID.fromString(userId), request));
	}

	// STEP 3: 소득분위, 가구원 수, 가정형태, 개인해당항목, 관심분야 저장
	@Operation(summary = "가구 정보 및 관심사 저장/수정", description = "온보딩 STEP 3 또는 추천 기준 수정에서 소득분위, 가구원 수, 가정형태, 개인해당항목, 관심분야를 저장합니다.")
	@PutMapping("/household")
	public ApiResponse<OnboardingStepResponse> saveHousehold(
			@AuthenticationPrincipal String userId,
			@Valid @RequestBody ProfileHouseholdRequest request
	) {
		return ApiResponse.ok(userProfileService.saveHousehold(UUID.fromString(userId), request));
	}

	// STEP 4: 온보딩 완료 처리. 추천 결과는 /api/v1/scholarships/curated에서 조회 시 계산합니다.
	@Operation(summary = "온보딩 완료", description = "온보딩을 완료 처리합니다. 추천 결과는 /api/v1/scholarships/curated에서 조회 시 계산합니다.")
	@PostMapping("/complete")
	public ApiResponse<OnboardingCompleteResponse> complete(@AuthenticationPrincipal String userId) {
		return ApiResponse.ok(userProfileService.complete(UUID.fromString(userId)));
	}
}
