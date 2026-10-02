package com.wishconnect.domain.auth.controller;

import com.wishconnect.domain.auth.dto.request.LoginRequest;
import com.wishconnect.domain.auth.dto.response.AdminLoginResponse;
import com.wishconnect.domain.auth.dto.response.AdminSessionResponse;
import com.wishconnect.domain.auth.service.AdminAuthService;
import com.wishconnect.global.common.ApiResponse;
import com.wishconnect.global.jwt.AdminAuthCookie;
import com.wishconnect.global.jwt.AdminSessionSlidingFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
			description = """
					활성 LOCAL 계정의 비밀번호와 ADMIN 역할을 확인합니다. 성공하면 화면 접근용 HttpOnly 쿠키와
					관리자 API 호출용 Access Token을 발급합니다.

					**실패 제한**: 같은 아이디로 5회 실패하면 15분간 잠깁니다(없는 아이디도 똑같이 셉니다).
					같은 IP 에서 15분 안에 20회 실패해도 15분간 잠깁니다. 성공하면 아이디 카운터가 초기화됩니다.

					실패 응답(401 LOGIN_FAILED / 429 ADMIN_LOGIN_LOCKED)의 data 에 failedCount, maxFailures,
					remainingAttempts, lockMinutes, locked, lockScope(ACCOUNT|IP), lockRemainingSeconds 가 담깁니다.
					""")
	@PostMapping("/login")
	public ResponseEntity<ApiResponse<AdminLoginResponse>> login(
			@Valid @RequestBody LoginRequest request,
			HttpServletRequest servletRequest) {
		AdminLoginResponse result = adminAuthService.login(request, servletRequest.getRemoteAddr());
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

	@Operation(summary = "관리자 세션 연장",
			description = """
					콘솔 우측 상단 "연장" 버튼용. 현재 토큰과 같은 세션으로 새 Access Token 을 발급합니다.
					만료 = min(지금 + 30분, 최초 로그인 + 8시간). 응답의 accessToken 으로 이후 요청의
					Authorization 헤더를 바꾸고, 쿠키도 함께 갱신됩니다.

					최초 로그인 후 8시간이 지났으면 401 ADMIN_SESSION_EXPIRED 입니다(다시 로그인).
					일반 로그인(/api/v1/auth/login)으로 받은 토큰은 401 ADMIN_SESSION_REQUIRED 입니다.

					참고: 이 버튼이 아니어도 관리자 API 를 호출하면(활동) 응답 헤더 X-Admin-Access-Token 으로
					새 토큰이 내려옵니다. X-Admin-Background: true 를 붙인 요청은 연장하지 않습니다.
					""")
	@PostMapping("/extend")
	public ResponseEntity<ApiResponse<AdminSessionResponse>> extend(HttpServletRequest servletRequest) {
		AdminSessionResponse result = adminAuthService.extend(AdminSessionSlidingFilter.resolveToken(servletRequest));
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, createCookie(
						result.accessToken(), result.remainingSeconds(), servletRequest.isSecure()).toString())
				.body(ApiResponse.ok(result));
	}

	@Operation(summary = "관리자 세션 남은 시간",
			description = "만료 시각·남은 초·절대 만료 시각을 돌려줍니다. 호출해도 세션은 연장되지 않습니다.")
	@GetMapping("/session")
	public ApiResponse<AdminSessionResponse> session(HttpServletRequest servletRequest) {
		return ApiResponse.ok(adminAuthService.status(AdminSessionSlidingFilter.resolveToken(servletRequest)));
	}

	private ResponseCookie createCookie(String value, long maxAgeSeconds, boolean secure) {
		return AdminAuthCookie.create(value, maxAgeSeconds, secure);
	}
}
