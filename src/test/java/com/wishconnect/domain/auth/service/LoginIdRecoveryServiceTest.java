package com.wishconnect.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LoginIdRecoveryServiceTest {

	private static final String EMAIL = "user@example.com";
	private static final String NAME = "홍길동";

	@Mock
	private UserRepository userRepository;
	@Mock
	private StringRedisTemplate redisTemplate;
	@Mock
	private ValueOperations<String, String> valueOps;
	@Mock
	private MailService mailService;

	private LoginIdRecoveryService service;

	@BeforeEach
	void setUp() {
		service = new LoginIdRecoveryService(userRepository, redisTemplate, mailService,
				new EmailVerificationProperties(300, 1800, 60));
	}

	private User localUser() {
		User user = User.createLocal(EMAIL, "user01", "encoded", NAME, "010");
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		return user;
	}

	@Test
	@DisplayName("이메일과 이름이 일치하는 LOCAL 계정에만 인증 코드를 발송한다")
	void requestCode_existingLocalUser() {
		User user = localUser();
		given(redisTemplate.opsForValue()).willReturn(valueOps);
		given(redisTemplate.hasKey(anyString())).willReturn(false);
		given(userRepository.findByEmailIgnoreCaseAndNameAndLoginTypeAndDeletedAtIsNull(
				EMAIL, NAME, LoginType.LOCAL)).willReturn(Optional.of(user));

		long expiresIn = service.requestCode("USER@example.com ", " 홍길동 ");

		assertThat(expiresIn).isEqualTo(300);
		verify(valueOps).set(eq("login-id:find:code:" + user.getId()), anyString(), any());
		verify(mailService).sendLoginIdFindCode(eq(EMAIL), anyString());
	}

	@Test
	@DisplayName("계정이 없어도 동일한 만료시간을 반환하고 메일만 보내지 않는다")
	void requestCode_unknownUserDoesNotEnumerate() {
		given(redisTemplate.opsForValue()).willReturn(valueOps);
		given(redisTemplate.hasKey(anyString())).willReturn(false);
		given(userRepository.findByEmailIgnoreCaseAndNameAndLoginTypeAndDeletedAtIsNull(
				EMAIL, NAME, LoginType.LOCAL)).willReturn(Optional.empty());

		assertThat(service.requestCode(EMAIL, NAME)).isEqualTo(300);
		verify(mailService, never()).sendLoginIdFindCode(anyString(), anyString());
	}

	@Test
	@DisplayName("재전송 쿨다운 중에는 TOO_MANY_REQUESTS")
	void requestCode_cooldown() {
		given(redisTemplate.hasKey(anyString())).willReturn(true);

		assertThatThrownBy(() -> service.requestCode(EMAIL, NAME))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
	}

	@Test
	@DisplayName("인증번호가 맞으면 아이디를 반환하고 코드를 한 번만 사용한다")
	void verifyAndFind_success() {
		User user = localUser();
		String codeKey = "login-id:find:code:" + user.getId();
		given(redisTemplate.opsForValue()).willReturn(valueOps);
		given(userRepository.findByEmailIgnoreCaseAndNameAndLoginTypeAndDeletedAtIsNull(
				EMAIL, NAME, LoginType.LOCAL)).willReturn(Optional.of(user));
		given(valueOps.get(codeKey)).willReturn("123456");

		assertThat(service.verifyAndFind(EMAIL, NAME, "123456")).isEqualTo("user01");
		verify(redisTemplate).delete(codeKey);
	}

	@Test
	@DisplayName("계정 없음과 틀린 코드는 같은 복구 실패 응답을 사용한다")
	void verifyAndFind_failureIsGeneric() {
		given(userRepository.findByEmailIgnoreCaseAndNameAndLoginTypeAndDeletedAtIsNull(
				EMAIL, NAME, LoginType.LOCAL)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.verifyAndFind(EMAIL, NAME, "123456"))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.ACCOUNT_RECOVERY_VERIFICATION_FAILED);
	}
}
