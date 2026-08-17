package com.wishconnect.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.auth.config.EmailVerificationProperties;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailVerificationServiceTest {

	private static final String EMAIL = "user@example.com";
	private static final String CODE_KEY = "email:verify:code:" + EMAIL;
	private static final String VERIFIED_KEY = "email:verify:done:" + EMAIL;
	private static final String COOLDOWN_KEY = "email:verify:cooldown:" + EMAIL;

	@Mock
	private StringRedisTemplate redisTemplate;
	@Mock
	private ValueOperations<String, String> valueOps;
	@Mock
	private MailService mailService;
	@Mock
	private UserRepository userRepository;

	private EmailVerificationService service;

	@BeforeEach
	void setUp() {
		given(redisTemplate.opsForValue()).willReturn(valueOps);
		service = new EmailVerificationService(redisTemplate, mailService, userRepository,
				new EmailVerificationProperties(300, 1800, 60));
	}

	@Test
	@DisplayName("코드 발송: 쿨다운 없으면 코드 저장 후 SES 발송하고 유효시간(초) 반환")
	void sendCode_success() {
		given(redisTemplate.hasKey(COOLDOWN_KEY)).willReturn(false);

		long expiresIn = service.sendCode(EMAIL);

		assertThat(expiresIn).isEqualTo(300);
		verify(mailService).sendVerificationCode(eq(EMAIL), anyString());
		verify(valueOps).set(eq(CODE_KEY), anyString(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("코드 발송: 쿨다운 중이면 TOO_MANY_REQUESTS, 발송 안 함")
	void sendCode_cooldown() {
		given(redisTemplate.hasKey(COOLDOWN_KEY)).willReturn(true);

		assertThatThrownBy(() -> service.sendCode(EMAIL))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
		verify(mailService, never()).sendVerificationCode(anyString(), anyString());
	}

	@Test
	@DisplayName("코드 확인: 일치하면 인증완료 상태 기록 + 코드 삭제")
	void verifyCode_success() {
		given(valueOps.get(CODE_KEY)).willReturn("123456");

		service.verifyCode(EMAIL, "123456");

		verify(redisTemplate).delete(CODE_KEY);
		verify(valueOps).set(eq(VERIFIED_KEY), eq("1"), org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("코드 확인: 저장된 코드 없으면 VERIFICATION_CODE_EXPIRED")
	void verifyCode_expired() {
		given(valueOps.get(CODE_KEY)).willReturn(null);

		assertThatThrownBy(() -> service.verifyCode(EMAIL, "123456"))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_CODE_EXPIRED);
	}

	@Test
	@DisplayName("코드 확인: 코드 불일치면 INVALID_VERIFICATION_CODE")
	void verifyCode_mismatch() {
		given(valueOps.get(CODE_KEY)).willReturn("111111");

		assertThatThrownBy(() -> service.verifyCode(EMAIL, "222222"))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);
	}

	@Test
	@DisplayName("이메일 사용 가능 여부: LOCAL 계정 존재하면 false")
	void isEmailAvailable() {
		given(userRepository.existsByEmailAndLoginTypeAndDeletedAtIsNull(EMAIL, LoginType.LOCAL)).willReturn(true);
		assertThat(service.isEmailAvailable(EMAIL)).isFalse();

		given(userRepository.existsByEmailAndLoginTypeAndDeletedAtIsNull(EMAIL, LoginType.LOCAL)).willReturn(false);
		assertThat(service.isEmailAvailable(EMAIL)).isTrue();
	}

	@Test
	@DisplayName("인증 상태 확인")
	void isVerified() {
		given(redisTemplate.hasKey(VERIFIED_KEY)).willReturn(true);
		assertThat(service.isVerified(EMAIL)).isTrue();
	}
}
