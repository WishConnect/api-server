package com.wishconnect.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.auth.config.EmailVerificationProperties;
import com.wishconnect.domain.auth.dto.response.PasswordResetVerifyResponse;
import com.wishconnect.domain.auth.util.RecoveryKeyHasher;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

	private static final String EMAIL = "user@example.com";
	private static final String LOGIN_ID = "user01";
	private static final String RESET_TOKEN = "reset-token";
	private static final String TOKEN_KEY = "password:reset:token:" + RecoveryKeyHasher.hash(RESET_TOKEN);

	@Mock
	private UserRepository userRepository;
	@Mock
	private StringRedisTemplate redisTemplate;
	@Mock
	private ValueOperations<String, String> valueOps;
	@Mock
	private MailService mailService;
	@Mock
	private PasswordEncoder passwordEncoder;
	@Mock
	private RefreshTokenService refreshTokenService;

	private PasswordResetService service;

	@BeforeEach
	void setUp() {
		service = new PasswordResetService(userRepository, redisTemplate, mailService, passwordEncoder,
				refreshTokenService, new EmailVerificationProperties(300, 1800, 60));
	}

	private static User localUser() {
		User user = User.createLocal(EMAIL, "user01", "oldEncoded", "홍길동", "010-1234-5678");
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		return user;
	}

	@Test
	@DisplayName("재설정 요청: LOCAL 가입자면 코드 발송하고 유효시간 반환")
	void requestReset_existingUser() {
		User user = localUser();
		given(redisTemplate.opsForValue()).willReturn(valueOps);
		given(redisTemplate.hasKey(anyString())).willReturn(false);
		given(userRepository.findByLoginIdAndEmailIgnoreCaseAndLoginTypeAndDeletedAtIsNull(
				LOGIN_ID, EMAIL, LoginType.LOCAL)).willReturn(Optional.of(user));

		long expiresIn = service.requestReset("USER01", "USER@example.com");

		assertThat(expiresIn).isEqualTo(300);
		verify(mailService).sendPasswordResetCode(eq(EMAIL), anyString());
		verify(valueOps).set(eq("password:reset:code:" + user.getId()), anyString(), any());
	}

	@Test
	@DisplayName("재설정 요청: 미가입/소셜이면 발송 안 하지만 동일 응답(계정 열거 방지)")
	void requestReset_noLocalUser() {
		given(redisTemplate.opsForValue()).willReturn(valueOps);
		given(redisTemplate.hasKey(anyString())).willReturn(false);
		given(userRepository.findByLoginIdAndEmailIgnoreCaseAndLoginTypeAndDeletedAtIsNull(
				LOGIN_ID, EMAIL, LoginType.LOCAL)).willReturn(Optional.empty());

		long expiresIn = service.requestReset(LOGIN_ID, EMAIL);

		assertThat(expiresIn).isEqualTo(300);
		verify(mailService, never()).sendPasswordResetCode(anyString(), anyString());
	}

	@Test
	@DisplayName("재설정 요청: 쿨다운 중이면 TOO_MANY_REQUESTS")
	void requestReset_cooldown() {
		given(redisTemplate.hasKey(anyString())).willReturn(true);

		assertThatThrownBy(() -> service.requestReset(LOGIN_ID, EMAIL))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
		verify(mailService, never()).sendPasswordResetCode(anyString(), anyString());
	}

	@Test
	@DisplayName("코드 검증 성공 시 원문을 Redis에 남기지 않는 일회성 재설정 토큰을 발급한다")
	void verifyCode_success() {
		User user = localUser();
		String codeKey = "password:reset:code:" + user.getId();
		given(redisTemplate.opsForValue()).willReturn(valueOps);
		given(userRepository.findByLoginIdAndEmailIgnoreCaseAndLoginTypeAndDeletedAtIsNull(
				LOGIN_ID, EMAIL, LoginType.LOCAL)).willReturn(Optional.of(user));
		given(valueOps.get(codeKey)).willReturn("123456");

		PasswordResetVerifyResponse response = service.verifyCode(LOGIN_ID, EMAIL, "123456");

		assertThat(response.resetToken()).isNotBlank();
		assertThat(response.expiresIn()).isEqualTo(300);
		verify(redisTemplate).delete(codeKey);
		verify(valueOps).set(argThat(key -> key.startsWith("password:reset:token:")),
				eq(user.getId().toString()), any());
	}

	@Test
	@DisplayName("일회성 토큰과 정책 통과 시 비밀번호를 변경하고 기존 세션을 끊는다")
	void resetPassword_success() {
		User user = localUser();
		given(redisTemplate.opsForValue()).willReturn(valueOps);
		given(valueOps.get(TOKEN_KEY)).willReturn(user.getId().toString());
		given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
		given(passwordEncoder.encode("NewPass1!")).willReturn("newEncoded");

		service.resetPassword(RESET_TOKEN, "NewPass1!");

		assertThat(user.getPassword()).isEqualTo("newEncoded");
		verify(redisTemplate).delete(TOKEN_KEY);
		verify(refreshTokenService).delete(user.getId());
	}

	@Test
	@DisplayName("인증 코드가 없거나 틀리면 같은 복구 실패 응답을 사용한다")
	void verifyCode_failureIsGeneric() {
		User user = localUser();
		given(redisTemplate.opsForValue()).willReturn(valueOps);
		given(userRepository.findByLoginIdAndEmailIgnoreCaseAndLoginTypeAndDeletedAtIsNull(
				LOGIN_ID, EMAIL, LoginType.LOCAL)).willReturn(Optional.of(user));
		given(valueOps.get("password:reset:code:" + user.getId())).willReturn("111111");

		assertThatThrownBy(() -> service.verifyCode(LOGIN_ID, EMAIL, "222222"))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.ACCOUNT_RECOVERY_VERIFICATION_FAILED);
	}

	@Test
	@DisplayName("일치하는 계정이 없어도 코드 검증 응답으로 계정 존재 여부를 숨긴다")
	void verifyCode_noUserIsGeneric() {
		given(userRepository.findByLoginIdAndEmailIgnoreCaseAndLoginTypeAndDeletedAtIsNull(
				LOGIN_ID, EMAIL, LoginType.LOCAL)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.verifyCode(LOGIN_ID, EMAIL, "123456"))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.ACCOUNT_RECOVERY_VERIFICATION_FAILED);
	}

	@Test
	@DisplayName("재설정 토큰이 만료되면 비밀번호와 세션을 건드리지 않는다")
	void resetPassword_expiredToken() {
		given(redisTemplate.opsForValue()).willReturn(valueOps);
		given(valueOps.get(TOKEN_KEY)).willReturn(null);

		assertThatThrownBy(() -> service.resetPassword(RESET_TOKEN, "NewPass1!"))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.PASSWORD_RESET_TOKEN_INVALID);
		verify(refreshTokenService, never()).delete(any());
	}

	@Test
	@DisplayName("재설정: 새 비밀번호 정책 위반 시 INVALID_PASSWORD_FORMAT")
	void resetPassword_invalidNewPassword() {
		User user = localUser();
		given(redisTemplate.opsForValue()).willReturn(valueOps);
		given(valueOps.get(TOKEN_KEY)).willReturn(user.getId().toString());
		given(userRepository.findById(user.getId())).willReturn(Optional.of(user));

		assertThatThrownBy(() -> service.resetPassword(RESET_TOKEN, "weak"))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.INVALID_PASSWORD_FORMAT);
		verify(redisTemplate, never()).delete(TOKEN_KEY);
		verify(refreshTokenService, never()).delete(any());
	}

	@Test
	@DisplayName("재설정 토큰의 사용자 식별자가 손상되면 만료된 토큰과 동일하게 처리한다")
	void resetPassword_malformedUserId() {
		given(redisTemplate.opsForValue()).willReturn(valueOps);
		given(valueOps.get(TOKEN_KEY)).willReturn("invalid-user-id");

		assertThatThrownBy(() -> service.resetPassword(RESET_TOKEN, "NewPass1!"))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.PASSWORD_RESET_TOKEN_INVALID);
		verify(userRepository, never()).findById(any());
		verify(refreshTokenService, never()).delete(any());
	}
}
