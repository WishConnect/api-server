package com.wishconnect.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.auth.dto.request.LoginRequest;
import com.wishconnect.domain.auth.dto.response.AdminLoginResponse;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.entity.UserRole;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import com.wishconnect.global.jwt.JwtProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("관리자 로그인 서비스")
class AdminAuthServiceTest {

	@Mock private UserRepository userRepository;
	@Mock private PasswordEncoder passwordEncoder;
	@Mock private JwtProvider jwtProvider;

	private AdminAuthService service;
	private final LoginRequest request = new LoginRequest("ADMIN01", "password");

	@BeforeEach
	void setUp() {
		service = new AdminAuthService(userRepository, passwordEncoder, jwtProvider);
	}

	@Test
	@DisplayName("ADMIN LOCAL 계정이면 관리자 Access Token을 발급한다")
	void loginAdmin() {
		User admin = user(UserRole.ADMIN);
		given(userRepository.findByLoginIdAndLoginTypeAndDeletedAtIsNull("admin01", LoginType.LOCAL))
				.willReturn(Optional.of(admin));
		given(passwordEncoder.matches("password", "encoded")).willReturn(true);
		given(jwtProvider.createAccessToken(admin.getId(), "ADMIN")).willReturn("admin-token");
		given(jwtProvider.getAccessTokenValidity()).willReturn(1_800_000L);

		AdminLoginResponse response = service.login(request);

		assertThat(response.accessToken()).isEqualTo("admin-token");
		assertThat(response.expiresInSeconds()).isEqualTo(1800);
		assertThat(response.name()).isEqualTo("관리자");
	}

	@Test
	@DisplayName("비밀번호가 맞아도 USER 역할이면 로그인 실패로 숨긴다")
	void rejectNormalUser() {
		User user = user(UserRole.USER);
		given(userRepository.findByLoginIdAndLoginTypeAndDeletedAtIsNull("admin01", LoginType.LOCAL))
				.willReturn(Optional.of(user));

		assertThatThrownBy(() -> service.login(request))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.LOGIN_FAILED);
		verify(jwtProvider, never()).createAccessToken(user.getId(), "ADMIN");
	}

	@Test
	@DisplayName("ADMIN의 비밀번호가 틀려도 토큰을 발급하지 않는다")
	void rejectWrongPassword() {
		User admin = user(UserRole.ADMIN);
		given(userRepository.findByLoginIdAndLoginTypeAndDeletedAtIsNull("admin01", LoginType.LOCAL))
				.willReturn(Optional.of(admin));
		given(passwordEncoder.matches("password", "encoded")).willReturn(false);

		assertThatThrownBy(() -> service.login(request))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.LOGIN_FAILED);
		verify(jwtProvider, never()).createAccessToken(admin.getId(), "ADMIN");
	}

	private User user(UserRole role) {
		User user = User.createLocal("admin@example.com", "admin01", "encoded", "관리자", "010");
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(user, "role", role);
		return user;
	}
}
