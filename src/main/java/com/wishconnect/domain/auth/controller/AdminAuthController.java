package com.wishconnect.domain.auth.controller;

import com.wishconnect.domain.auth.dto.request.LoginRequest;
import com.wishconnect.domain.auth.dto.response.AdminLoginResponse;
import com.wishconnect.domain.auth.service.AdminAuthService;
import com.wishconnect.global.common.ApiResponse;
import com.wishconnect.global.jwt.AdminAuthCookie;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 - 인증", description = "관리자 콘솔과 운영 Swagger 접근 인증")
@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

	private final AdminAuthService adminAuthService;

	@Operation(summary = "관리자 로그인",
			description = "활성 LOCAL 계정의 비밀번호와 ADMIN 역할을 확인합니다. 성공하면 화면 접근용 HttpOnly 쿠키와 관리자 API 호출용 Access Token을 발급합니다.")
	@PostMapping("/login")
	public ResponseEntity<ApiResponse<AdminLoginResponse>> login(
			@Valid @RequestBody LoginRequest request,
			HttpServletRequest servletRequest) {
		AdminLoginResponse result = adminAuthService.login(request);
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, createCookie(
						result.accessToken(), result.expiresInSeconds(), servletRequest.isSecure()).toString())
				.body(ApiResponse.ok(result));
	}

	@Operation(summary = "관리자 로그아웃", description = "관리자 화면 접근 쿠키를 삭제합니다.")
	@PostMapping("/logout")
	public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest servletRequest) {
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, createCookie("", 0, servletRequest.isSecure()).toString())
				.body(ApiResponse.ok());
	}

	private ResponseCookie createCookie(String value, long maxAgeSeconds, boolean secure) {
		return ResponseCookie.from(AdminAuthCookie.NAME, value)
				.httpOnly(true)
				.secure(secure)
				.sameSite("Strict")
				.path("/")
				.maxAge(Duration.ofSeconds(maxAgeSeconds))
				.build();
	}
}
