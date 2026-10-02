package com.wishconnect.domain.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wishconnect.domain.auth.dto.response.AdminLoginAttemptResponse;
import com.wishconnect.domain.auth.dto.response.AdminLoginResponse;
import com.wishconnect.domain.auth.service.AdminAuthService;
import com.wishconnect.global.config.SecurityConfig;
import com.wishconnect.global.exception.CustomDetailException;
import com.wishconnect.global.exception.ErrorCode;
import com.wishconnect.global.jwt.JwtAuthenticationEntryPoint;
import com.wishconnect.global.jwt.JwtProvider;
import com.wishconnect.global.jwt.WithdrawnTokenStore;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminAuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class})
@DisplayName("관리자 인증 API")
class AdminAuthControllerTest {

	@Autowired private MockMvc mockMvc;
	@MockBean private AdminAuthService adminAuthService;
	@MockBean private JwtProvider jwtProvider;
	@MockBean private WithdrawnTokenStore withdrawnTokenStore;

	@Test
	@DisplayName("로그인 성공 시 HttpOnly Strict 쿠키와 Access Token을 반환한다")
	void login() throws Exception {
		given(adminAuthService.login(any(), any()))
				.willReturn(new AdminLoginResponse("admin-token", 1800, "관리자"));

		mockMvc.perform(post("/api/v1/admin/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"loginId":"admin01","password":"password"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.accessToken").value("admin-token"))
				.andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.containsString("wc_admin_access=admin-token")))
				.andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.containsString("HttpOnly")))
				.andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.containsString("SameSite=Strict")));
	}

	@Test
	@DisplayName("로그인 실패 응답에 실패 횟수·잠금 기준·남은 잠금 시간을 담는다")
	void loginFailureCarriesAttemptInfo() throws Exception {
		given(adminAuthService.login(any(), any())).willThrow(new CustomDetailException(ErrorCode.LOGIN_FAILED,
				new AdminLoginAttemptResponse(3, 5, 2, 15, false, null, 0)));

		mockMvc.perform(post("/api/v1/admin/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"loginId":"admin01","password":"wrong"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data.failedCount").value(3))
				.andExpect(jsonPath("$.data.maxFailures").value(5))
				.andExpect(jsonPath("$.data.remainingAttempts").value(2))
				.andExpect(jsonPath("$.data.lockMinutes").value(15));
	}

	@Test
	@DisplayName("잠긴 상태면 429 와 남은 잠금 시간을 준다")
	void lockedLogin() throws Exception {
		given(adminAuthService.login(any(), any())).willThrow(new CustomDetailException(
				ErrorCode.ADMIN_LOGIN_LOCKED, new AdminLoginAttemptResponse(5, 5, 0, 15, true, "ACCOUNT", 812)));

		mockMvc.perform(post("/api/v1/admin/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"loginId":"admin01","password":"wrong"}
								"""))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.data.locked").value(true))
				.andExpect(jsonPath("$.data.lockScope").value("ACCOUNT"))
				.andExpect(jsonPath("$.data.lockRemainingSeconds").value(812));
	}

	@Test
	@DisplayName("로그아웃은 관리자 쿠키를 즉시 만료시킨다")
	void logout() throws Exception {
		mockMvc.perform(post("/api/v1/admin/auth/logout"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.containsString("Max-Age=0")));
	}
}
