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
import com.wishconnect.domain.auth.dto.response.LoginIdCheckResponse;
import com.wishconnect.domain.auth.dto.response.SocialLoginResponse;
import com.wishconnect.domain.auth.dto.response.TokenResponse;
import com.wishconnect.domain.auth.service.AuthService;
import com.wishconnect.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "인증", description = "회원가입·로그인·소셜로그인·토큰 재발급")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	/** 이메일 회원가입. 이메일 인증을 먼저 완료해야 하며, 필수 약관 4종에 모두 동의해야 한다. */
	/**
	 * 회원가입 화면의 아이디 "중복 확인" 버튼.
	 *
	 * <p>대소문자를 구분하지 않는다(Junho 와 junho 는 같은 아이디로 본다).
	 * 형식이 어긋나면 400 이라 화면에서 안내 문구를 바로 띄울 수 있다.
	 */
	@GetMapping("/login-id/check")
	public ApiResponse<LoginIdCheckResponse> checkLoginId(@RequestParam @NotBlank String loginId) {
		return ApiResponse.ok(new LoginIdCheckResponse(authService.isLoginIdAvailable(loginId)));
	}

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
		return ApiResponse.ok(authService.signup(request));
	}

	/** 이메일·비밀번호 로그인. 성공 시 accessToken/refreshToken 을 발급한다. */
	@PostMapping("/login")
	public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		return ApiResponse.ok(authService.login(request));
	}

	/** 카카오 인가코드로 로그인한다. 미가입 사용자면 자동 가입 후 토큰을 발급한다. */
	@PostMapping("/kakao/login")
	public ApiResponse<KakaoLoginResponse> kakaoLogin(@RequestBody KakaoLoginRequest request) {
		return ApiResponse.ok(authService.kakaoLogin(request.code(), request.redirectUri()));
	}

	/** 구글 인가코드로 로그인한다. 미가입 사용자면 자동 가입 후 토큰을 발급한다. */
	@PostMapping("/google/login")
	public ApiResponse<SocialLoginResponse> googleLogin(@RequestBody GoogleLoginRequest request) {
		return ApiResponse.ok(authService.googleLogin(request.code(), request.redirectUri()));
	}

	/** 네이버 인가코드로 로그인한다. 미가입 사용자면 자동 가입 후 토큰을 발급한다. */
	@PostMapping("/naver/login")
	public ApiResponse<SocialLoginResponse> naverLogin(@RequestBody NaverLoginRequest request) {
		return ApiResponse.ok(authService.naverLogin(request.code(), request.state()));
	}

	/** refreshToken 으로 accessToken 을 재발급한다. */
	@PostMapping("/refresh")
	public ApiResponse<TokenResponse> refresh(@Valid @RequestBody TokenRefreshRequest request) {
		return ApiResponse.ok(authService.refresh(request.refreshToken()));
	}

	/** refreshToken 을 폐기해 로그아웃 처리한다. */
	@PostMapping("/logout")
	public ApiResponse<Void> logout(@AuthenticationPrincipal String userId) {
		authService.logout(UUID.fromString(userId));
		return ApiResponse.ok();
	}
}
