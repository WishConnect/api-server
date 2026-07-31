package com.wishconnect.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.auth.client.GoogleApiClient;
import com.wishconnect.domain.auth.client.KakaoApiClient;
import com.wishconnect.domain.auth.client.NaverApiClient;
import com.wishconnect.domain.auth.client.dto.GoogleTokenResponse;
import com.wishconnect.domain.auth.client.dto.GoogleUserResponse;
import com.wishconnect.domain.auth.client.dto.KakaoTokenResponse;
import com.wishconnect.domain.auth.client.dto.KakaoUserResponse;
import com.wishconnect.domain.auth.client.dto.KakaoUserResponse.KakaoAccount;
import com.wishconnect.domain.auth.client.dto.KakaoUserResponse.KakaoAccount.Profile;
import com.wishconnect.domain.auth.client.dto.NaverTokenResponse;
import com.wishconnect.domain.auth.client.dto.NaverUserResponse;
import com.wishconnect.domain.auth.dto.response.SocialLoginResponse;
import com.wishconnect.domain.auth.dto.request.LoginRequest;
import com.wishconnect.domain.auth.dto.request.SignupRequest;
import com.wishconnect.domain.auth.dto.response.KakaoLoginResponse;
import com.wishconnect.domain.auth.dto.response.LoginResponse;
import com.wishconnect.domain.auth.dto.response.SignupResponse;
import com.wishconnect.domain.auth.dto.response.TokenResponse;
import com.wishconnect.domain.auth.dto.request.AgreementItem;
import com.wishconnect.domain.common.repository.RegionRepository;
import com.wishconnect.domain.user.entity.AgreementType;
import com.wishconnect.domain.user.entity.Gender;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.Nationality;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserAgreementRepository;
import com.wishconnect.domain.user.repository.UserProfileRepository;
import com.wishconnect.domain.user.repository.UserRepository;
import java.util.List;
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
	private UserProfileRepository userProfileRepository;
	@Mock
	private UserAgreementRepository userAgreementRepository;
	@Mock
	private RegionRepository regionRepository;
	@Mock
	private PasswordEncoder passwordEncoder;
	@Mock
	private JwtProvider jwtProvider;
	@Mock
	private RefreshTokenService refreshTokenService;
	@Mock
	private EmailVerificationService emailVerificationService;
	@Mock
	private KakaoApiClient kakaoApiClient;
	@Mock
	private GoogleApiClient googleApiClient;
	@Mock
	private NaverApiClient naverApiClient;

	@InjectMocks
	private AuthService authService;

	private static final List<AgreementItem> ALL_AGREED = List.of(
			new AgreementItem(AgreementType.TERMS, true),
			new AgreementItem(AgreementType.PRIVACY, true),
			new AgreementItem(AgreementType.THIRD_PARTY, true),
			new AgreementItem(AgreementType.AGE_14, true));

	private static User userWithId(User user) {
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		return user;
	}

	private void stubTokenIssue() {
		given(jwtProvider.createAccessToken(any(), any())).willReturn("access-token");
		given(jwtProvider.createRefreshToken(any())).willReturn("refresh-token");
	}

	@Nested
	@DisplayName("회원가입")
	class Signup {

		private SignupRequest request(String password, List<AgreementItem> agreements) {
			return new SignupRequest("user@example.com", password, "홍길동", "010-1234-5678",
					2002, Gender.FEMALE, Nationality.DOMESTIC, "서울", agreements);
		}

		@Test
		@DisplayName("성공 시 사용자·프로필·약관을 저장하고 JWT 를 발급한다")
		void success() {
			SignupRequest request = request("Abcd1234!", ALL_AGREED);
			given(emailVerificationService.isVerified(request.email())).willReturn(true);
			given(userRepository.existsByEmailAndLoginType(request.email(), LoginType.LOCAL)).willReturn(false);
			given(passwordEncoder.encode(request.password())).willReturn("encoded");
			given(regionRepository.findByName("서울")).willReturn(Optional.empty());
			given(userRepository.save(any(User.class)))
					.willAnswer(invocation -> userWithId(invocation.getArgument(0)));
			stubTokenIssue();

			SignupResponse response = authService.signup(request);

			assertThat(response.userId()).isNotNull();
			assertThat(response.accessToken()).isEqualTo("access-token");
			verify(userProfileRepository).save(any());
			verify(userAgreementRepository).saveAll(any());
			verify(emailVerificationService).clearVerified(request.email());
			verify(refreshTokenService).save(any(UUID.class), eq("refresh-token"));
		}

		@Test
		@DisplayName("이메일 미인증 시 EMAIL_NOT_VERIFIED")
		void emailNotVerified() {
			SignupRequest request = request("Abcd1234!", ALL_AGREED);
			given(emailVerificationService.isVerified(request.email())).willReturn(false);

			assertThatThrownBy(() -> authService.signup(request))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
			verify(userRepository, never()).save(any());
		}

		@Test
		@DisplayName("비밀번호 정책 위반 시 INVALID_PASSWORD_FORMAT")
		void invalidPassword() {
			SignupRequest request = request("abcdefgh", ALL_AGREED);
			given(emailVerificationService.isVerified(request.email())).willReturn(true);

			assertThatThrownBy(() -> authService.signup(request))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.INVALID_PASSWORD_FORMAT);
			verify(userRepository, never()).save(any());
		}

		@Test
		@DisplayName("이메일 중복 시 DUPLICATE_EMAIL")
		void duplicateEmail() {
			SignupRequest request = request("Abcd1234!", ALL_AGREED);
			given(emailVerificationService.isVerified(request.email())).willReturn(true);
			given(userRepository.existsByEmailAndLoginType(request.email(), LoginType.LOCAL)).willReturn(true);

			assertThatThrownBy(() -> authService.signup(request))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_EMAIL);
			verify(userRepository, never()).save(any());
		}

		@Test
		@DisplayName("필수 약관 미동의 시 AGREEMENT_REQUIRED")
		void agreementRequired() {
			List<AgreementItem> missing = List.of(
					new AgreementItem(AgreementType.TERMS, true),
					new AgreementItem(AgreementType.PRIVACY, true),
					new AgreementItem(AgreementType.THIRD_PARTY, true),
					new AgreementItem(AgreementType.AGE_14, false));
			SignupRequest request = request("Abcd1234!", missing);
			given(emailVerificationService.isVerified(request.email())).willReturn(true);
			given(userRepository.existsByEmailAndLoginType(request.email(), LoginType.LOCAL)).willReturn(false);

			assertThatThrownBy(() -> authService.signup(request))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.AGREEMENT_REQUIRED);
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
			given(userRepository.findByEmailAndLoginType(request.email(), LoginType.LOCAL)).willReturn(Optional.of(user));
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
			given(userRepository.findByEmailAndLoginType(request.email(), LoginType.LOCAL)).willReturn(Optional.empty());

			assertThatThrownBy(() -> authService.login(request))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
		}

		@Test
		@DisplayName("비밀번호 불일치면 LOGIN_FAILED")
		void wrongPassword() {
			User user = userWithId(User.createLocal("user@example.com", "encoded", "홍길동", "010"));
			given(userRepository.findByEmailAndLoginType(request.email(), LoginType.LOCAL)).willReturn(Optional.of(user));
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
			given(kakaoApiClient.getToken("code", null))
					.willReturn(new KakaoTokenResponse("kakao-access", "bearer", null, 3600, null));
			given(kakaoApiClient.getUserInfo("kakao-access"))
					.willReturn(kakaoUser(111L, "k@kakao.com", "카카오닉"));
			User existing = userWithId(User.createKakao(111L, "k@kakao.com", "카카오닉"));
			given(userRepository.findByKakaoId(111L)).willReturn(Optional.of(existing));
			stubTokenIssue();

			KakaoLoginResponse response = authService.kakaoLogin("code", null);

			assertThat(response.isNewUser()).isFalse();
			assertThat(response.user().loginType()).isEqualTo(LoginType.KAKAO);
			verify(userRepository, never()).save(any());
		}

		@Test
		@DisplayName("신규면 자동가입하고 isNewUser=true")
		void newUser() {
			given(kakaoApiClient.getToken("code", null))
					.willReturn(new KakaoTokenResponse("kakao-access", "bearer", null, 3600, null));
			given(kakaoApiClient.getUserInfo("kakao-access"))
					.willReturn(kakaoUser(222L, "new@kakao.com", "신규닉"));
			given(userRepository.findByKakaoId(222L)).willReturn(Optional.empty());
			given(userRepository.save(any(User.class)))
					.willAnswer(invocation -> userWithId(invocation.getArgument(0)));
			stubTokenIssue();

			KakaoLoginResponse response = authService.kakaoLogin("code", null);

			assertThat(response.isNewUser()).isTrue();
			assertThat(response.user().name()).isEqualTo("신규닉");
			verify(userRepository).save(any(User.class));
		}

		@Test
		@DisplayName("이메일 미수신 시 자리표시자로 가입하지 않고 KAKAO_EMAIL_REQUIRED 로 막는다")
		void rejectsSignupWithoutEmail() {
			given(kakaoApiClient.getToken("code", null))
					.willReturn(new KakaoTokenResponse("kakao-access", "bearer", null, 3600, null));
			given(kakaoApiClient.getUserInfo("kakao-access"))
					.willReturn(kakaoUser(333L, null, "닉네임"));
			given(userRepository.findByKakaoId(333L)).willReturn(Optional.empty());

			assertThatThrownBy(() -> authService.kakaoLogin("code", null))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.KAKAO_EMAIL_REQUIRED);
			verify(userRepository, never()).save(any(User.class));
		}

		@Test
		@DisplayName("기존 회원의 자리표시자 이메일은 재로그인 시 실제 이메일로 교체된다")
		void backfillsPlaceholderEmail() {
			given(kakaoApiClient.getToken("code", null))
					.willReturn(new KakaoTokenResponse("kakao-access", "bearer", null, 3600, null));
			given(kakaoApiClient.getUserInfo("kakao-access"))
					.willReturn(kakaoUser(444L, "real@kakao.com", "닉네임"));
			User existing = userWithId(User.createKakao(444L, "kakao_444@wishconnect.kr", "닉네임"));
			given(userRepository.findByKakaoId(444L)).willReturn(Optional.of(existing));
			stubTokenIssue();

			authService.kakaoLogin("code", null);

			assertThat(existing.getEmail()).isEqualTo("real@kakao.com");
		}

		@Test
		@DisplayName("이미 실제 이메일을 가진 기존 회원의 이메일은 건드리지 않는다")
		void keepsRealEmail() {
			given(kakaoApiClient.getToken("code", null))
					.willReturn(new KakaoTokenResponse("kakao-access", "bearer", null, 3600, null));
			given(kakaoApiClient.getUserInfo("kakao-access"))
					.willReturn(kakaoUser(555L, "provider@kakao.com", "닉네임"));
			User existing = userWithId(User.createKakao(555L, "mine@gmail.com", "닉네임"));
			given(userRepository.findByKakaoId(555L)).willReturn(Optional.of(existing));
			stubTokenIssue();

			authService.kakaoLogin("code", null);

			assertThat(existing.getEmail()).isEqualTo("mine@gmail.com");
		}

		@Test
		@DisplayName("code 가 비어있으면 INVALID_KAKAO_CODE")
		void blankCode() {
			assertThatThrownBy(() -> authService.kakaoLogin("  ", null))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.INVALID_KAKAO_CODE);
		}
	}

	@Nested
	@DisplayName("구글 로그인")
	class GoogleLogin {

		private GoogleTokenResponse token() {
			return new GoogleTokenResponse("g-access", "Bearer", "id-token", 3600, "openid email profile");
		}

		@Test
		@DisplayName("기존 회원이면 로그인, isNewUser=false")
		void existingUser() {
			given(googleApiClient.getToken("code", null)).willReturn(token());
			given(googleApiClient.getUserInfo("g-access"))
					.willReturn(new GoogleUserResponse("sub-123", "g@google.com", "구글이름"));
			User existing = userWithId(User.createSocial(LoginType.GOOGLE, "sub-123", "g@google.com", "구글이름"));
			given(userRepository.findByLoginTypeAndProviderId(LoginType.GOOGLE, "sub-123"))
					.willReturn(Optional.of(existing));
			stubTokenIssue();

			SocialLoginResponse response = authService.googleLogin("code", null);

			assertThat(response.isNewUser()).isFalse();
			assertThat(response.user().loginType()).isEqualTo(LoginType.GOOGLE);
			verify(userRepository, never()).save(any());
		}

		@Test
		@DisplayName("신규면 자동가입, isNewUser=true")
		void newUser() {
			given(googleApiClient.getToken("code", null)).willReturn(token());
			given(googleApiClient.getUserInfo("g-access"))
					.willReturn(new GoogleUserResponse("sub-999", "new@google.com", "신규구글"));
			given(userRepository.findByLoginTypeAndProviderId(LoginType.GOOGLE, "sub-999"))
					.willReturn(Optional.empty());
			given(userRepository.save(any(User.class)))
					.willAnswer(invocation -> userWithId(invocation.getArgument(0)));
			stubTokenIssue();

			SocialLoginResponse response = authService.googleLogin("code", null);

			assertThat(response.isNewUser()).isTrue();
			assertThat(response.user().name()).isEqualTo("신규구글");
		}

		@Test
		@DisplayName("이메일 미수신 시 대체 이메일로 가입")
		void fallbackEmail() {
			given(googleApiClient.getToken("code", null)).willReturn(token());
			given(googleApiClient.getUserInfo("g-access"))
					.willReturn(new GoogleUserResponse("sub-777", null, "구글"));
			given(userRepository.findByLoginTypeAndProviderId(LoginType.GOOGLE, "sub-777"))
					.willReturn(Optional.empty());
			given(userRepository.save(any(User.class)))
					.willAnswer(invocation -> userWithId(invocation.getArgument(0)));
			stubTokenIssue();

			authService.googleLogin("code", null);

			org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
			verify(userRepository).save(captor.capture());
			assertThat(captor.getValue().getEmail()).isEqualTo("google_sub-777@wishconnect.kr");
		}

		@Test
		@DisplayName("code 비어있으면 INVALID_GOOGLE_CODE")
		void blankCode() {
			assertThatThrownBy(() -> authService.googleLogin(" ", null))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.INVALID_GOOGLE_CODE);
		}
	}

	@Nested
	@DisplayName("네이버 로그인")
	class NaverLogin {

		private NaverTokenResponse token() {
			return new NaverTokenResponse("n-access", "bearer", "n-refresh", "3600", null, null);
		}

		private NaverUserResponse naverUser(String id, String email, String name) {
			return new NaverUserResponse("00", "success",
					new NaverUserResponse.Response(id, email, name, "닉네임"));
		}

		@Test
		@DisplayName("신규면 자동가입, isNewUser=true, loginType=NAVER")
		void newUser() {
			given(naverApiClient.getToken("code", "state")).willReturn(token());
			given(naverApiClient.getUserInfo("n-access")).willReturn(naverUser("nid-1", "n@naver.com", "네이버이름"));
			given(userRepository.findByLoginTypeAndProviderId(LoginType.NAVER, "nid-1"))
					.willReturn(Optional.empty());
			given(userRepository.save(any(User.class)))
					.willAnswer(invocation -> userWithId(invocation.getArgument(0)));
			stubTokenIssue();

			SocialLoginResponse response = authService.naverLogin("code", "state");

			assertThat(response.isNewUser()).isTrue();
			assertThat(response.user().loginType()).isEqualTo(LoginType.NAVER);
			assertThat(response.user().name()).isEqualTo("네이버이름");
		}

		@Test
		@DisplayName("code 비어있으면 INVALID_NAVER_CODE")
		void blankCode() {
			assertThatThrownBy(() -> authService.naverLogin(" ", "state"))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.INVALID_NAVER_CODE);
		}

		@Test
		@DisplayName("state 비어있으면 INVALID_NAVER_STATE")
		void blankState() {
			assertThatThrownBy(() -> authService.naverLogin("code", " "))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.INVALID_NAVER_STATE);
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
			User user = userWithId(User.createLocal("user@example.com", "encoded", "홍길동", "010"));
			given(userRepository.findById(userId)).willReturn(Optional.of(user));
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
