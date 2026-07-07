package com.wishconnect.domain.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wishconnect.domain.auth.dto.response.KakaoLoginResponse;
import com.wishconnect.domain.auth.dto.response.LoginResponse;
import com.wishconnect.domain.auth.dto.response.SignupResponse;
import com.wishconnect.domain.auth.dto.response.TokenResponse;
import com.wishconnect.domain.auth.service.AuthService;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.global.config.SecurityConfig;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import com.wishconnect.global.jwt.JwtAuthenticationEntryPoint;
import com.wishconnect.global.jwt.JwtProvider;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class})
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private AuthService authService;

	@MockBean
	private JwtProvider jwtProvider;

	@Nested
	@DisplayName("POST /api/v1/auth/signup")
	class Signup {

		private static final String VALID_BODY = """
				{"email":"user@example.com","password":"Test1234!","name":"홍길동","phone":"010-1234-5678"}
				""";

		@Test
		@DisplayName("성공 시 201과 토큰을 반환한다")
		void success() throws Exception {
			given(authService.signup(any()))
					.willReturn(new SignupResponse(UUID.randomUUID(), "access", "refresh"));

			mockMvc.perform(post("/api/v1/auth/signup")
							.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.data.accessToken").value("access"))
					.andExpect(jsonPath("$.data.refreshToken").value("refresh"))
					.andExpect(jsonPath("$.message").doesNotExist());
		}

		@Test
		@DisplayName("필수값 누락 시 400 INVALID_INPUT")
		void validationFail() throws Exception {
			mockMvc.perform(post("/api/v1/auth/signup")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"email":"","password":"Test1234!","name":"홍길동","phone":"010"}
									"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.message").value(ErrorCode.INVALID_INPUT.getMessage()));
		}

		@Test
		@DisplayName("이메일 중복 시 409 DUPLICATE_EMAIL")
		void duplicateEmail() throws Exception {
			given(authService.signup(any())).willThrow(new CustomException(ErrorCode.DUPLICATE_EMAIL));

			mockMvc.perform(post("/api/v1/auth/signup")
							.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.message").value(ErrorCode.DUPLICATE_EMAIL.getMessage()));
		}

		@Test
		@DisplayName("비밀번호 정책 위반 시 400 INVALID_PASSWORD_FORMAT")
		void invalidPassword() throws Exception {
			given(authService.signup(any()))
					.willThrow(new CustomException(ErrorCode.INVALID_PASSWORD_FORMAT));

			mockMvc.perform(post("/api/v1/auth/signup")
							.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value(ErrorCode.INVALID_PASSWORD_FORMAT.getMessage()));
		}
	}

	@Nested
	@DisplayName("POST /api/v1/auth/login")
	class Login {

		private static final String BODY = """
				{"email":"user@example.com","password":"Test1234!"}
				""";

		@Test
		@DisplayName("성공 시 200과 사용자 정보를 반환한다")
		void success() throws Exception {
			given(authService.login(any())).willReturn(
					new LoginResponse("access", "refresh",
							new LoginResponse.UserInfo(UUID.randomUUID(), "홍길동", false)));

			mockMvc.perform(post("/api/v1/auth/login")
							.contentType(MediaType.APPLICATION_JSON).content(BODY))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.data.user.name").value("홍길동"))
					.andExpect(jsonPath("$.data.user.onboardingCompleted").value(false));
		}

		@Test
		@DisplayName("비밀번호 불일치 시 401 LOGIN_FAILED")
		void loginFailed() throws Exception {
			given(authService.login(any())).willThrow(new CustomException(ErrorCode.LOGIN_FAILED));

			mockMvc.perform(post("/api/v1/auth/login")
							.contentType(MediaType.APPLICATION_JSON).content(BODY))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.message").value(ErrorCode.LOGIN_FAILED.getMessage()));
		}

		@Test
		@DisplayName("존재하지 않는 계정 시 404 USER_NOT_FOUND")
		void userNotFound() throws Exception {
			given(authService.login(any())).willThrow(new CustomException(ErrorCode.USER_NOT_FOUND));

			mockMvc.perform(post("/api/v1/auth/login")
							.contentType(MediaType.APPLICATION_JSON).content(BODY))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.message").value(ErrorCode.USER_NOT_FOUND.getMessage()));
		}
	}

	@Nested
	@DisplayName("POST /api/v1/auth/kakao/login")
	class KakaoLogin {

		private static final String BODY = """
				{"code":"auth-code"}
				""";

		@Test
		@DisplayName("성공 시 200과 isNewUser/loginType 을 반환한다")
		void success() throws Exception {
			given(authService.kakaoLogin(any())).willReturn(
					new KakaoLoginResponse("access", "refresh", true,
							new KakaoLoginResponse.UserInfo(UUID.randomUUID(), "카카오닉", LoginType.KAKAO, false)));

			mockMvc.perform(post("/api/v1/auth/kakao/login")
							.contentType(MediaType.APPLICATION_JSON).content(BODY))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.isNewUser").value(true))
					.andExpect(jsonPath("$.data.user.loginType").value("KAKAO"));
		}

		@Test
		@DisplayName("유효하지 않은 인가코드 시 400 INVALID_KAKAO_CODE")
		void invalidCode() throws Exception {
			given(authService.kakaoLogin(any())).willThrow(new CustomException(ErrorCode.INVALID_KAKAO_CODE));

			mockMvc.perform(post("/api/v1/auth/kakao/login")
							.contentType(MediaType.APPLICATION_JSON).content(BODY))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value(ErrorCode.INVALID_KAKAO_CODE.getMessage()));
		}

		@Test
		@DisplayName("카카오 사용자 정보 조회 실패 시 502 KAKAO_USER_INFO_FAILED")
		void userInfoFailed() throws Exception {
			given(authService.kakaoLogin(any()))
					.willThrow(new CustomException(ErrorCode.KAKAO_USER_INFO_FAILED));

			mockMvc.perform(post("/api/v1/auth/kakao/login")
							.contentType(MediaType.APPLICATION_JSON).content(BODY))
					.andExpect(status().isBadGateway())
					.andExpect(jsonPath("$.message").value(ErrorCode.KAKAO_USER_INFO_FAILED.getMessage()));
		}
	}

	@Nested
	@DisplayName("POST /api/v1/auth/refresh")
	class Refresh {

		private static final String BODY = """
				{"refreshToken":"some-refresh-token"}
				""";

		@Test
		@DisplayName("성공 시 200과 새 토큰 쌍을 반환한다")
		void success() throws Exception {
			given(authService.refresh(any())).willReturn(new TokenResponse("new-access", "new-refresh"));

			mockMvc.perform(post("/api/v1/auth/refresh")
							.contentType(MediaType.APPLICATION_JSON).content(BODY))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.accessToken").value("new-access"))
					.andExpect(jsonPath("$.data.refreshToken").value("new-refresh"));
		}

		@Test
		@DisplayName("유효하지 않은 토큰 시 401 INVALID_TOKEN")
		void invalidToken() throws Exception {
			given(authService.refresh(any())).willThrow(new CustomException(ErrorCode.INVALID_TOKEN));

			mockMvc.perform(post("/api/v1/auth/refresh")
							.contentType(MediaType.APPLICATION_JSON).content(BODY))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.message").value(ErrorCode.INVALID_TOKEN.getMessage()));
		}
	}

	@Nested
	@DisplayName("POST /api/v1/auth/logout")
	class Logout {

		@Test
		@DisplayName("유효한 Access Token 으로 요청 시 200")
		void success() throws Exception {
			UUID userId = UUID.randomUUID();
			given(jwtProvider.validateToken("valid-token")).willReturn(true);
			given(jwtProvider.getUserId("valid-token")).willReturn(userId);

			mockMvc.perform(post("/api/v1/auth/logout")
							.header("Authorization", "Bearer valid-token"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true));
		}

		@Test
		@DisplayName("인증 없이 요청 시 401 UNAUTHORIZED")
		void unauthorized() throws Exception {
			mockMvc.perform(post("/api/v1/auth/logout"))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.message").value(ErrorCode.UNAUTHORIZED.getMessage()));
		}
	}
}
