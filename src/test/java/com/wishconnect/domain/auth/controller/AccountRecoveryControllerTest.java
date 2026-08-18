package com.wishconnect.domain.auth.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wishconnect.domain.auth.dto.response.PasswordResetVerifyResponse;
import com.wishconnect.domain.auth.service.LoginIdRecoveryService;
import com.wishconnect.domain.auth.service.PasswordResetService;
import com.wishconnect.global.config.SecurityConfig;
import com.wishconnect.global.jwt.JwtAuthenticationEntryPoint;
import com.wishconnect.global.jwt.JwtProvider;
import com.wishconnect.global.jwt.WithdrawnTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({LoginIdRecoveryController.class, PasswordController.class})
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class})
class AccountRecoveryControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@MockBean
	private LoginIdRecoveryService loginIdRecoveryService;
	@MockBean
	private PasswordResetService passwordResetService;
	@MockBean
	private JwtProvider jwtProvider;
	@MockBean
	private WithdrawnTokenStore withdrawnTokenStore;

	@Test
	@DisplayName("아이디 찾기 요청은 이메일과 이름을 받고 인증번호 만료시간을 반환한다")
	void loginIdFindRequest() throws Exception {
		given(loginIdRecoveryService.requestCode(anyString(), anyString())).willReturn(300L);

		mockMvc.perform(post("/api/v1/auth/login-id/find-request")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"user@example.com\",\"name\":\"홍길동\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.expiresIn").value(300));
	}

	@Test
	@DisplayName("아이디 찾기 인증 성공 시 아이디를 반환한다")
	void loginIdFindVerify() throws Exception {
		given(loginIdRecoveryService.verifyAndFind(anyString(), anyString(), anyString()))
				.willReturn("user01");

		mockMvc.perform(post("/api/v1/auth/login-id/find")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"user@example.com\",\"name\":\"홍길동\",\"code\":\"123456\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.loginId").value("user01"));
	}

	@Test
	@DisplayName("인증번호는 정확히 6자리 숫자여야 한다")
	void recoveryCodeValidation() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login-id/find")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"user@example.com\",\"name\":\"홍길동\",\"code\":\"12ab\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("비밀번호 찾기 요청은 아이디와 이메일을 필수로 받는다")
	void passwordResetRequest() throws Exception {
		given(passwordResetService.requestReset(anyString(), anyString())).willReturn(300L);

		mockMvc.perform(post("/api/v1/auth/password/reset-request")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"loginId\":\"user01\",\"email\":\"user@example.com\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.expiresIn").value(300));
	}

	@Test
	@DisplayName("비밀번호 인증번호 검증 성공 시 일회성 재설정 토큰을 반환한다")
	void passwordResetVerify() throws Exception {
		given(passwordResetService.verifyCode(anyString(), anyString(), anyString()))
				.willReturn(new PasswordResetVerifyResponse("reset-token", 300));

		mockMvc.perform(post("/api/v1/auth/password/verify")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"loginId\":\"user01\",\"email\":\"user@example.com\",\"code\":\"123456\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.resetToken").value("reset-token"));
	}

	@Test
	@DisplayName("새 비밀번호 변경은 재설정 토큰을 필수로 받는다")
	void passwordReset() throws Exception {
		mockMvc.perform(post("/api/v1/auth/password/reset")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"resetToken\":\"reset-token\",\"newPassword\":\"NewPass1!\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.reset").value(true));
	}
}
