package com.wishconnect.domain.auth.controller;

import com.wishconnect.domain.auth.dto.request.GoogleLoginRequest;
import com.wishconnect.domain.auth.dto.request.KakaoLoginRequest;
import com.wishconnect.domain.auth.dto.request.LoginRequest;
import com.wishconnect.domain.auth.dto.request.NaverLoginRequest;
import com.wishconnect.domain.auth.dto.request.SignupRequest;
import com.wishconnect.domain.auth.dto.request.TokenRefreshRequest;
import com.wishconnect.domain.auth.dto.response.KakaoLoginResponse;
import com.wishconnect.domain.auth.dto.response.LoginResponse;
import com.wishconnect.domain.auth.dto.response.SignupResponse;
import com.wishconnect.domain.auth.dto.response.SocialLoginResponse;
import com.wishconnect.domain.auth.dto.response.TokenResponse;
import com.wishconnect.domain.auth.service.AuthService;
import com.wishconnect.global.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
		return ApiResponse.ok(authService.signup(request));
	}

	@PostMapping("/login")
	public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		return ApiResponse.ok(authService.login(request));
	}

	@PostMapping("/kakao/login")
	public ApiResponse<KakaoLoginResponse> kakaoLogin(@RequestBody KakaoLoginRequest request) {
		return ApiResponse.ok(authService.kakaoLogin(request.code()));
	}

	@PostMapping("/google/login")
	public ApiResponse<SocialLoginResponse> googleLogin(@RequestBody GoogleLoginRequest request) {
		return ApiResponse.ok(authService.googleLogin(request.code()));
	}

	@PostMapping("/naver/login")
	public ApiResponse<SocialLoginResponse> naverLogin(@RequestBody NaverLoginRequest request) {
		return ApiResponse.ok(authService.naverLogin(request.code(), request.state()));
	}

	@PostMapping("/refresh")
	public ApiResponse<TokenResponse> refresh(@Valid @RequestBody TokenRefreshRequest request) {
		return ApiResponse.ok(authService.refresh(request.refreshToken()));
	}

	@PostMapping("/logout")
	public ApiResponse<Void> logout(@AuthenticationPrincipal String userId) {
		authService.logout(UUID.fromString(userId));
		return ApiResponse.ok();
	}
}
