package com.wishconnect.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.auth.client.KakaoApiClient;
import com.wishconnect.domain.auth.client.dto.KakaoTokenResponse;
import com.wishconnect.domain.auth.client.dto.KakaoUserResponse;
import com.wishconnect.domain.auth.client.dto.KakaoUserResponse.KakaoAccount;
import com.wishconnect.domain.auth.client.dto.KakaoUserResponse.KakaoAccount.Profile;
import com.wishconnect.domain.auth.dto.request.LoginRequest;
import com.wishconnect.domain.auth.dto.request.SignupRequest;
import com.wishconnect.domain.auth.dto.response.KakaoLoginResponse;
import com.wishconnect.domain.auth.dto.response.LoginResponse;
import com.wishconnect.domain.auth.dto.response.SignupResponse;
import com.wishconnect.domain.auth.dto.response.TokenResponse;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import com.wishconnect.global.jwt.JwtProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	private UserRepository userRepository;
	@Mock
	private PasswordEncoder passwordEncoder;
	@Mock
	private JwtProvider jwtProvider;
	@Mock
	private RefreshTokenService refreshTokenService;
	@Mock
	private KakaoApiClient kakaoApiClient;

	@InjectMocks
	private AuthService authService;

	private static User userWithId(User user) {
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		return user;
	}

	private void stubTokenIssue() {
		given(jwtProvider.createAccessToken(any())).willReturn("access-token");
		given(jwtProvider.createRefreshToken(any())).willReturn("refresh-token");
	}

	@Nested
	@DisplayName("회원가입")
	class Signup {

		private final SignupRequest request =
				new SignupRequest("user@example.com", "Abcd1234!", "홍길동", "010-1234-5678");

		@Test
		@DisplayName("성공 시 사용자를 저장하고 JWT 를 발급한다")
		void success() {
			given(userRepository.existsByEmail(request.email())).willReturn(false);
			given(passwordEncoder.encode(request.password())).willReturn("encoded");
			given(userRepository.save(any(User.class)))
					.willAnswer(invocation -> userWithId(invocation.getArgument(0)));
			stubTokenIssue();

			SignupResponse response = authService.signup(request);

			assertThat(response.userId()).isNotNull();
			assertThat(response.accessToken()).isEqualTo("access-token");
			assertThat(response.refreshToken()).isEqualTo("refresh-token");
			verify(refreshTokenService).save(any(UUID.class), eq("refresh-token"));
		}

		@Test
		@DisplayName("비밀번호 정책 위반 시 INVALID_PASSWORD_FORMAT")
		void invalidPassword() {
			SignupRequest weak =
					new SignupRequest("user@example.com", "abcdefgh", "홍길동", "010-1234-5678");

			assertThatThrownBy(() -> authService.signup(weak))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.INVALID_PASSWORD_FORMAT);
			verify(userRepository, never()).save(any());
		}

		@Test
		@DisplayName("이메일 중복 시 DUPLICATE_EMAIL")
		void duplicateEmail() {
			given(userRepository.existsByEmail(request.email())).willReturn(true);

			assertThatThrownBy(() -> authService.signup(request))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_EMAIL);
			verify(userRepository, never()).save(any());
		}
	}

	@Nested
	@DisplayName("기본 로그인")
	class Login {

		private final LoginRequest request = new LoginRequest("user@example.com", "Abcd1234!");

		@Test
		@DisplayName("성공 시 JWT 와 사용자 정보를 반환한다")
		void success() {
			User user = userWithId(User.createLocal("user@example.com", "encoded", "홍길동", "010"));
			given(userRepository.findByEmail(request.email())).willReturn(Optional.of(user));
			given(passwordEncoder.matches(request.password(), "encoded")).willReturn(true);
			stubTokenIssue();

			LoginResponse response = authService.login(request);

			assertThat(response.accessToken()).isEqualTo("access-token");
			assertThat(response.user().name()).isEqualTo("홍길동");
			assertThat(response.user().onboardingCompleted()).isFalse();
		}

		@Test
		@DisplayName("존재하지 않는 이메일이면 USER_NOT_FOUND")
		void userNotFound() {
			given(userRepository.findByEmail(request.email())).willReturn(Optional.empty());

			assertThatThrownBy(() -> authService.login(request))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
		}

		@Test
		@DisplayName("비밀번호 불일치면 LOGIN_FAILED")
		void wrongPassword() {
			User user = userWithId(User.createLocal("user@example.com", "encoded", "홍길동", "010"));
			given(userRepository.findByEmail(request.email())).willReturn(Optional.of(user));
			given(passwordEncoder.matches(request.password(), "encoded")).willReturn(false);

			assertThatThrownBy(() -> authService.login(request))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.LOGIN_FAILED);
		}
	}

	@Nested
	@DisplayName("카카오 로그인")
	class KakaoLogin {

		private KakaoUserResponse kakaoUser(Long id, String email, String nickname) {
			return new KakaoUserResponse(id, new KakaoAccount(email, new Profile(nickname)));
		}

		@Test
		@DisplayName("기존 회원이면 로그인하고 isNewUser=false")
		void existingUser() {
			given(kakaoApiClient.getToken("code"))
					.willReturn(new KakaoTokenResponse("kakao-access", "bearer", null, 3600, null));
			given(kakaoApiClient.getUserInfo("kakao-access"))
					.willReturn(kakaoUser(111L, "k@kakao.com", "카카오닉"));
			User existing = userWithId(User.createKakao(111L, "k@kakao.com", "카카오닉"));
			given(userRepository.findByKakaoId(111L)).willReturn(Optional.of(existing));
			stubTokenIssue();

			KakaoLoginResponse response = authService.kakaoLogin("code");

			assertThat(response.isNewUser()).isFalse();
			assertThat(response.user().loginType()).isEqualTo(LoginType.KAKAO);
			verify(userRepository, never()).save(any());
		}

		@Test
		@DisplayName("신규면 자동가입하고 isNewUser=true")
		void newUser() {
			given(kakaoApiClient.getToken("code"))
					.willReturn(new KakaoTokenResponse("kakao-access", "bearer", null, 3600, null));
			given(kakaoApiClient.getUserInfo("kakao-access"))
					.willReturn(kakaoUser(222L, "new@kakao.com", "신규닉"));
			given(userRepository.findByKakaoId(222L)).willReturn(Optional.empty());
			given(userRepository.save(any(User.class)))
					.willAnswer(invocation -> userWithId(invocation.getArgument(0)));
			stubTokenIssue();

			KakaoLoginResponse response = authService.kakaoLogin("code");

			assertThat(response.isNewUser()).isTrue();
			assertThat(response.user().name()).isEqualTo("신규닉");
			verify(userRepository).save(any(User.class));
		}

		@Test
		@DisplayName("이메일 미수신 시 대체 이메일로 가입한다")
		void fallbackEmail() {
			given(kakaoApiClient.getToken("code"))
					.willReturn(new KakaoTokenResponse("kakao-access", "bearer", null, 3600, null));
			given(kakaoApiClient.getUserInfo("kakao-access"))
					.willReturn(kakaoUser(333L, null, "닉네임"));
			given(userRepository.findByKakaoId(333L)).willReturn(Optional.empty());
			given(userRepository.save(any(User.class)))
					.willAnswer(invocation -> userWithId(invocation.getArgument(0)));
			stubTokenIssue();

			authService.kakaoLogin("code");

			org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
			verify(userRepository).save(captor.capture());
			assertThat(captor.getValue().getEmail()).isEqualTo("kakao_333@wishconnect.kr");
		}

		@Test
		@DisplayName("code 가 비어있으면 INVALID_KAKAO_CODE")
		void blankCode() {
			assertThatThrownBy(() -> authService.kakaoLogin("  "))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.INVALID_KAKAO_CODE);
		}
	}

	@Nested
	@DisplayName("토큰 갱신")
	class Refresh {

		@Test
		@DisplayName("저장된 토큰과 일치하면 새 토큰 쌍을 발급한다")
		void success() {
			UUID userId = UUID.randomUUID();
			given(jwtProvider.validateToken("refresh-token")).willReturn(true);
			given(jwtProvider.getUserId("refresh-token")).willReturn(userId);
			given(refreshTokenService.find(userId)).willReturn(Optional.of("refresh-token"));
			stubTokenIssue();

			TokenResponse response = authService.refresh("refresh-token");

			assertThat(response.accessToken()).isEqualTo("access-token");
			assertThat(response.refreshToken()).isEqualTo("refresh-token");
		}

		@Test
		@DisplayName("서명/만료가 유효하지 않으면 INVALID_TOKEN")
		void invalidToken() {
			given(jwtProvider.validateToken("bad")).willReturn(false);

			assertThatThrownBy(() -> authService.refresh("bad"))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.INVALID_TOKEN);
		}

		@Test
		@DisplayName("Redis 에 저장된 토큰이 없으면 TOKEN_NOT_FOUND")
		void tokenNotFound() {
			UUID userId = UUID.randomUUID();
			given(jwtProvider.validateToken("refresh-token")).willReturn(true);
			given(jwtProvider.getUserId("refresh-token")).willReturn(userId);
			given(refreshTokenService.find(userId)).willReturn(Optional.empty());

			assertThatThrownBy(() -> authService.refresh("refresh-token"))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.TOKEN_NOT_FOUND);
		}

		@Test
		@DisplayName("저장된 토큰과 다르면 INVALID_TOKEN")
		void mismatch() {
			UUID userId = UUID.randomUUID();
			given(jwtProvider.validateToken("refresh-token")).willReturn(true);
			given(jwtProvider.getUserId("refresh-token")).willReturn(userId);
			given(refreshTokenService.find(userId)).willReturn(Optional.of("other-token"));

			assertThatThrownBy(() -> authService.refresh("refresh-token"))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.INVALID_TOKEN);
		}
	}

	@Test
	@DisplayName("로그아웃 시 Refresh Token 을 삭제한다")
	void logout() {
		UUID userId = UUID.randomUUID();

		authService.logout(userId);

		verify(refreshTokenService).delete(userId);
	}
}
