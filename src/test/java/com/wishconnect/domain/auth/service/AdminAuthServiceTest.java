package com.wishconnect.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.auth.dto.request.LoginRequest;
import com.wishconnect.domain.auth.dto.response.AdminLoginAttemptResponse;
import com.wishconnect.domain.auth.dto.response.AdminLoginResponse;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.entity.UserRole;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomDetailException;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import com.wishconnect.global.jwt.AdminSessionTokens;
import com.wishconnect.global.jwt.JwtProvider;
import java.time.Instant;
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
	@Mock private AdminSessionTokens sessionTokens;
	@Mock private AdminLoginAttemptService loginAttemptService;
	@Mock private com.wishconnect.global.audit.AdminAuditLogService adminAuditLogService;

	private AdminAuthService service;
	private static final String IP = "203.0.113.7";
	private final LoginRequest request = new LoginRequest("ADMIN01", "password");
	private final AdminLoginAttemptResponse failed =
			new AdminLoginAttemptResponse(1, 5, 4, 15, false, null, 0);

	@BeforeEach
	void setUp() {
		service = new AdminAuthService(userRepository, passwordEncoder, loginAttemptService, sessionTokens,
				adminAuditLogService);
	}

	@Test
	@DisplayName("ADMIN LOCAL 계정이면 관리자 Access Token을 발급한다")
	void loginAdmin() {
		User admin = user(UserRole.ADMIN);
		given(userRepository.findByLoginIdAndLoginTypeAndDeletedAtIsNull("admin01", LoginType.LOCAL))
				.willReturn(Optional.of(admin));
		given(passwordEncoder.matches("password", "encoded")).willReturn(true);
		Instant now = Instant.now();
		given(sessionTokens.issueNew(admin.getId())).willReturn(new AdminSessionTokens.Issued("admin-token",
				now.plusSeconds(1800), 1800, now, now.plusSeconds(28_800), 28_800, true));

		AdminLoginResponse response = service.login(request, IP);

		assertThat(response.accessToken()).isEqualTo("admin-token");
		assertThat(response.expiresInSeconds()).isEqualTo(1800);
		assertThat(response.sessionMaxExpiresAt()).isEqualTo(now.plusSeconds(28_800));
		assertThat(response.name()).isEqualTo("관리자");
		verify(loginAttemptService).reset("admin01");
	}

	@Test
	@DisplayName("없는 아이디도 실패로 세고 같은 응답을 준다 — 계정 존재 여부를 숨긴다")
	void unknownLoginIdCountsAsFailure() {
		given(userRepository.findByLoginIdAndLoginTypeAndDeletedAtIsNull("admin01", LoginType.LOCAL))
				.willReturn(Optional.empty());
		given(passwordEncoder.encode(anyString())).willReturn("dummy-hash");
		given(loginAttemptService.recordFailure("admin01", IP)).willReturn(failed);

		assertThatThrownBy(() -> service.login(request, IP))
				.isInstanceOf(CustomDetailException.class)
				.satisfies(e -> {
					assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.LOGIN_FAILED);
					assertThat(((CustomDetailException) e).getDetail()).isEqualTo(failed);
				});
		// 응답 시간 차이로 존재 여부가 드러나지 않도록 해시 비교를 한 번 한다.
		verify(passwordEncoder).matches("password", "dummy-hash");
	}

	@Test
	@DisplayName("잠긴 상태면 비밀번호를 확인하지 않고 429 잠금 응답을 준다")
	void lockedAccountSkipsPasswordCheck() {
		AdminLoginAttemptResponse locked = new AdminLoginAttemptResponse(5, 5, 0, 15, true, "ACCOUNT", 600);
		given(loginAttemptService.lockedStatus("admin01", IP)).willReturn(locked);

		assertThatThrownBy(() -> service.login(request, IP))
				.isInstanceOf(CustomDetailException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.ADMIN_LOGIN_LOCKED);
		verify(userRepository, never()).findByLoginIdAndLoginTypeAndDeletedAtIsNull(anyString(), any());
		verify(passwordEncoder, never()).matches(anyString(), anyString());
	}

	@Test
	@DisplayName("이번 실패로 기준 횟수에 도달하면 잠금 응답으로 바뀐다")
	void failureThatLocksReturnsLockedCode() {
		User admin = user(UserRole.ADMIN);
		given(userRepository.findByLoginIdAndLoginTypeAndDeletedAtIsNull("admin01", LoginType.LOCAL))
				.willReturn(Optional.of(admin));
		given(passwordEncoder.matches("password", "encoded")).willReturn(false);
		given(loginAttemptService.recordFailure("admin01", IP))
				.willReturn(new AdminLoginAttemptResponse(5, 5, 0, 15, true, "ACCOUNT", 900));

		assertThatThrownBy(() -> service.login(request, IP))
				.isInstanceOf(CustomDetailException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.ADMIN_LOGIN_LOCKED);
		verify(loginAttemptService, never()).reset(anyString());
	}

	@Test
	@DisplayName("비밀번호가 맞아도 USER 역할이면 로그인 실패로 숨긴다")
	void rejectNormalUser() {
		User user = user(UserRole.USER);
		given(userRepository.findByLoginIdAndLoginTypeAndDeletedAtIsNull("admin01", LoginType.LOCAL))
				.willReturn(Optional.of(user));
		given(loginAttemptService.recordFailure("admin01", IP)).willReturn(failed);

		assertThatThrownBy(() -> service.login(request, IP))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.LOGIN_FAILED);
		verify(sessionTokens, never()).issueNew(any());
	}

	@Test
	@DisplayName("ADMIN의 비밀번호가 틀려도 토큰을 발급하지 않는다")
	void rejectWrongPassword() {
		User admin = user(UserRole.ADMIN);
		given(userRepository.findByLoginIdAndLoginTypeAndDeletedAtIsNull("admin01", LoginType.LOCAL))
				.willReturn(Optional.of(admin));
		given(passwordEncoder.matches("password", "encoded")).willReturn(false);
		given(loginAttemptService.recordFailure("admin01", IP)).willReturn(failed);

		assertThatThrownBy(() -> service.login(request, IP))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.LOGIN_FAILED);
		verify(sessionTokens, never()).issueNew(any());
		verify(loginAttemptService).recordFailure("admin01", IP);
	}

	@Test
	@DisplayName("세션 연장: 일반 로그인 토큰이면 ADMIN_SESSION_REQUIRED")
	void extendRejectsPlainToken() {
		given(sessionTokens.parse("plain")).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.extend("plain"))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.ADMIN_SESSION_REQUIRED);
	}

	@Test
	@DisplayName("세션 연장: 절대 수명이 끝났으면 ADMIN_SESSION_EXPIRED")
	void extendRejectsAfterMaxLifetime() {
		given(sessionTokens.parse("old")).willReturn(Optional.of(new JwtProvider.AdminSessionClaims(
				UUID.randomUUID(), Instant.now().minusSeconds(29_000), Instant.now(), Instant.now().plusSeconds(10))));
		given(sessionTokens.extend("old")).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.extend("old"))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.ADMIN_SESSION_EXPIRED);
	}

	private User user(UserRole role) {
		User user = User.createLocal("admin@example.com", "admin01", "encoded", "관리자", "010");
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(user, "role", role);
		return user;
	}

	// --- 작업 기록(로그인 보안 추적) ---

	@Test
	@DisplayName("로그인 성공은 그 관리자로 ADMIN_LOGIN_SUCCESS 를 남긴다(IP 포함)")
	void recordsLoginSuccess() {
		User admin = user(UserRole.ADMIN);
		given(userRepository.findByLoginIdAndLoginTypeAndDeletedAtIsNull("admin01", LoginType.LOCAL))
				.willReturn(Optional.of(admin));
		given(passwordEncoder.matches("password", "encoded")).willReturn(true);
		Instant now = Instant.now();
		given(sessionTokens.issueNew(admin.getId())).willReturn(new AdminSessionTokens.Issued("t",
				now.plusSeconds(1800), 1800, now, now.plusSeconds(28_800), 28_800, true));

		service.login(request, IP);

		verify(adminAuditLogService).record(admin.getId(), com.wishconnect.global.audit.AdminAction.ADMIN_LOGIN_SUCCESS,
				"ADMIN_LOGIN", null, "로그인 · IP " + IP);
	}

	@Test
	@DisplayName("없는 아이디 실패는 행위자 없이, 가린 아이디·실패 횟수·IP 를 남긴다(비밀번호는 남기지 않음)")
	void recordsUnknownLoginFailure() {
		given(userRepository.findByLoginIdAndLoginTypeAndDeletedAtIsNull("admin01", LoginType.LOCAL))
				.willReturn(Optional.empty());
		given(passwordEncoder.encode(anyString())).willReturn("dummy-hash");
		given(loginAttemptService.recordFailure("admin01", IP)).willReturn(failed);

		assertThatThrownBy(() -> service.login(request, IP)).isInstanceOf(CustomDetailException.class);

		org.mockito.ArgumentCaptor<String> detail = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(adminAuditLogService).record(org.mockito.ArgumentMatchers.isNull(),
				org.mockito.ArgumentMatchers.eq(com.wishconnect.global.audit.AdminAction.ADMIN_LOGIN_FAILURE),
				org.mockito.ArgumentMatchers.eq("ADMIN_LOGIN"), org.mockito.ArgumentMatchers.isNull(), detail.capture());
		assertThat(detail.getValue()).contains("실패 1/5").contains("IP " + IP)
				.contains(AdminLoginAttemptService.mask("admin01")).doesNotContain("password").doesNotContain("admin01");
	}

	@Test
	@DisplayName("관리자 계정의 실패가 잠금으로 이어지면 그 계정으로 ADMIN_LOGIN_LOCKED 를 남긴다")
	void recordsLockAfterFailures() {
		User admin = user(UserRole.ADMIN);
		given(userRepository.findByLoginIdAndLoginTypeAndDeletedAtIsNull("admin01", LoginType.LOCAL))
				.willReturn(Optional.of(admin));
		given(passwordEncoder.matches("password", "encoded")).willReturn(false);
		given(loginAttemptService.recordFailure("admin01", IP))
				.willReturn(new AdminLoginAttemptResponse(5, 5, 0, 15, true, "ACCOUNT", 900));

		assertThatThrownBy(() -> service.login(request, IP)).isInstanceOf(CustomDetailException.class);

		verify(adminAuditLogService).record(org.mockito.ArgumentMatchers.eq(admin.getId()),
				org.mockito.ArgumentMatchers.eq(com.wishconnect.global.audit.AdminAction.ADMIN_LOGIN_LOCKED),
				org.mockito.ArgumentMatchers.eq("ADMIN_LOGIN"), org.mockito.ArgumentMatchers.isNull(),
				org.mockito.ArgumentMatchers.contains("잠금 범위 ACCOUNT"));
	}

	@Test
	@DisplayName("잠긴 상태에서 다시 시도해도 ADMIN_LOGIN_LOCKED 로 남긴다")
	void recordsRetryWhileLocked() {
		given(loginAttemptService.lockedStatus("admin01", IP))
				.willReturn(new AdminLoginAttemptResponse(5, 5, 0, 15, true, "IP", 600));

		assertThatThrownBy(() -> service.login(request, IP)).isInstanceOf(CustomDetailException.class);

		verify(adminAuditLogService).record(org.mockito.ArgumentMatchers.isNull(),
				org.mockito.ArgumentMatchers.eq(com.wishconnect.global.audit.AdminAction.ADMIN_LOGIN_LOCKED),
				org.mockito.ArgumentMatchers.eq("ADMIN_LOGIN"), org.mockito.ArgumentMatchers.isNull(),
				org.mockito.ArgumentMatchers.contains("잠긴 상태에서 다시 시도"));
	}

	@Test
	@DisplayName("로그아웃은 토큰으로 누군지 알 때만 남긴다")
	void recordsLogoutOnlyForKnownSession() {
		UUID adminId = UUID.randomUUID();
		given(sessionTokens.parse("valid")).willReturn(Optional.of(
				new JwtProvider.AdminSessionClaims(adminId, Instant.now(), Instant.now(), Instant.now().plusSeconds(60))));
		given(sessionTokens.parse("expired")).willReturn(Optional.empty());

		service.logout("valid", IP);
		service.logout("expired", IP);
		service.logout(null, IP);

		verify(adminAuditLogService).record(adminId, com.wishconnect.global.audit.AdminAction.ADMIN_LOGOUT,
				"ADMIN_LOGIN", null, "로그아웃 · IP " + IP);
		verify(adminAuditLogService, org.mockito.Mockito.times(1)).record(any(), any(), any(), any(), any());
	}
}
