package com.wishconnect.global.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

	private static final String SECRET = "test-secret-key-for-jwt-provider-unit-test-0123456789";
	private static final long VALIDITY = 60_000L;

	private final JwtProvider jwtProvider =
			new JwtProvider(new JwtProperties(SECRET, VALIDITY, VALIDITY));

	@Test
	@DisplayName("발급한 Access Token 은 유효하고 userId 를 복원할 수 있다")
	void createAndValidateAccessToken() {
		UUID userId = UUID.randomUUID();

		String token = jwtProvider.createAccessToken(userId, "USER");

		assertThat(jwtProvider.validateToken(token)).isTrue();
		assertThat(jwtProvider.getUserId(token)).isEqualTo(userId);
	}

	@Test
	@DisplayName("Access Token 에 담은 권한을 그대로 복원한다")
	void carriesRoleClaim() {
		String token = jwtProvider.createAccessToken(UUID.randomUUID(), "ADMIN");

		assertThat(jwtProvider.getRole(token)).isEqualTo("ADMIN");
	}

	@Test
	@DisplayName("Refresh Token 에는 권한을 담지 않는다")
	void refreshTokenHasNoRole() {
		String token = jwtProvider.createRefreshToken(UUID.randomUUID());

		assertThat(jwtProvider.getRole(token)).isNull();
	}

	@Test
	@DisplayName("위변조된 토큰은 무효다")
	void tamperedTokenIsInvalid() {
		String token = jwtProvider.createAccessToken(UUID.randomUUID(), "USER");

		assertThat(jwtProvider.validateToken(token + "tampered")).isFalse();
	}

	@Test
	@DisplayName("다른 secret 으로 서명한 토큰은 무효다")
	void tokenSignedWithOtherSecretIsInvalid() {
		JwtProvider other = new JwtProvider(
				new JwtProperties("another-secret-key-that-is-also-long-enough-0123456789", VALIDITY, VALIDITY));
		String token = other.createAccessToken(UUID.randomUUID(), "USER");

		assertThat(jwtProvider.validateToken(token)).isFalse();
	}

	@Test
	@DisplayName("만료된 토큰은 무효다")
	void expiredTokenIsInvalid() {
		JwtProvider expiringProvider =
				new JwtProvider(new JwtProperties(SECRET, -1_000L, -1_000L));
		String token = expiringProvider.createAccessToken(UUID.randomUUID(), "USER");

		assertThat(jwtProvider.validateToken(token)).isFalse();
	}
}
