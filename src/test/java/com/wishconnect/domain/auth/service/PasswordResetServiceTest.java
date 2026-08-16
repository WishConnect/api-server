package com.wishconnect.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.auth.config.EmailVerificationProperties;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordResetServiceTest {

	private static final String EMAIL = "user@example.com";
	private static final String CODE_KEY = "password:reset:code:" + EMAIL;
	private static final String COOLDOWN_KEY = "password:reset:cooldown:" + EMAIL;

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
		given(redisTemplate.opsForValue()).willReturn(valueOps);
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
		given(redisTemplate.hasKey(COOLDOWN_KEY)).willReturn(false);
		given(userRepository.findByEmailAndLoginType(EMAIL, LoginType.LOCAL))
				.willReturn(Optional.of(localUser()));

		long expiresIn = service.requestReset(EMAIL);

		assertThat(expiresIn).isEqualTo(300);
		verify(mailService).sendPasswordResetCode(org.mockito.ArgumentMatchers.eq(EMAIL), anyString());
	}

	@Test
	@DisplayName("재설정 요청: 미가입/소셜이면 발송 안 하지만 동일 응답(계정 열거 방지)")
	void requestReset_noLocalUser() {
		given(redisTemplate.hasKey(COOLDOWN_KEY)).willReturn(false);
		given(userRepository.findByEmailAndLoginType(EMAIL, LoginType.LOCAL)).willReturn(Optional.empty());

		long expiresIn = service.requestReset(EMAIL);

		assertThat(expiresIn).isEqualTo(300);
		verify(mailService, never()).sendPasswordResetCode(anyString(), anyString());
	}

	@Test
	@DisplayName("재설정 요청: 쿨다운 중이면 TOO_MANY_REQUESTS")
	void requestReset_cooldown() {
		given(redisTemplate.hasKey(COOLDOWN_KEY)).willReturn(true);

		assertThatThrownBy(() -> service.requestReset(EMAIL))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
		verify(mailService, never()).sendPasswordResetCode(anyString(), anyString());
	}

	@Test
	@DisplayName("재설정: 코드 일치 + 정책 통과 시 비밀번호 변경")
	void resetPassword_success() {
		User user = localUser();
		given(valueOps.get(CODE_KEY)).willReturn("123456");
		given(userRepository.findByEmailAndLoginType(EMAIL, LoginType.LOCAL)).willReturn(Optional.of(user));
		given(passwordEncoder.encode("NewPass1!")).willReturn("newEncoded");

		service.resetPassword(EMAIL, "123456", "NewPass1!");

		assertThat(user.getPassword()).isEqualTo("newEncoded");
		verify(redisTemplate).delete(CODE_KEY);
		// 계정 탈취 시 공격자 세션이 살아있지 않도록 기존 Refresh Token 을 지운다.
		verify(refreshTokenService).delete(user.getId());
	}

	@Test
	@DisplayName("재설정 실패 시에는 기존 세션을 건드리지 않는다")
	void resetPassword_failureKeepsSession() {
		given(valueOps.get(CODE_KEY)).willReturn("111111");

		assertThatThrownBy(() -> service.resetPassword(EMAIL, "222222", "NewPass1!"))
				.isInstanceOf(CustomException.class);

		verify(refreshTokenService, never()).delete(any());
	}

	@Test
	@DisplayName("재설정: 코드 없으면 VERIFICATION_CODE_EXPIRED")
	void resetPassword_expired() {
		given(valueOps.get(CODE_KEY)).willReturn(null);

		assertThatThrownBy(() -> service.resetPassword(EMAIL, "123456", "NewPass1!"))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_CODE_EXPIRED);
	}

	@Test
	@DisplayName("재설정: 코드 불일치면 INVALID_VERIFICATION_CODE")
	void resetPassword_mismatch() {
		given(valueOps.get(CODE_KEY)).willReturn("111111");

		assertThatThrownBy(() -> service.resetPassword(EMAIL, "222222", "NewPass1!"))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);
	}

	@Test
	@DisplayName("재설정: 새 비밀번호 정책 위반 시 INVALID_PASSWORD_FORMAT")
	void resetPassword_invalidNewPassword() {
		given(valueOps.get(CODE_KEY)).willReturn("123456");
		given(userRepository.findByEmailAndLoginType(EMAIL, LoginType.LOCAL)).willReturn(Optional.of(localUser()));

		assertThatThrownBy(() -> service.resetPassword(EMAIL, "123456", "weak"))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.INVALID_PASSWORD_FORMAT);
	}
}
