package com.wishconnect.global.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.wishconnect.global.config.AdminSecurityProperties;
import com.wishconnect.global.jwt.JwtProvider.AdminSessionClaims;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 세션 토큰 — 슬라이딩 + 절대 수명")
class AdminSessionTokensTest {

	private static final AdminSecurityProperties PROPERTIES = new AdminSecurityProperties(
			5, Duration.ofMinutes(15), 20, Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(8));

	private final JwtProvider jwtProvider = new JwtProvider(
			new JwtProperties("test-secret-test-secret-test-secret-0123456789", 1_800_000L, 1_209_600_000L));
	private final UUID userId = UUID.randomUUID();

	private AdminSessionTokens at(Instant now) {
		return new AdminSessionTokens(jwtProvider, PROPERTIES, Clock.fixed(now, ZoneOffset.UTC));
	}

	@Test
	@DisplayName("로그인하면 만료는 30분 뒤, 절대 만료는 8시간 뒤다")
	void issuesNewSession() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		AdminSessionTokens.Issued issued = at(now).issueNew(userId);

		assertThat(issued.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
		assertThat(issued.sessionMaxExpiresAt()).isEqualTo(now.plus(Duration.ofHours(8)));
		assertThat(issued.extendable()).isTrue();
		AdminSessionClaims claims = jwtProvider.parseAdminSession(issued.token()).orElseThrow();
		assertThat(claims.userId()).isEqualTo(userId);
		assertThat(claims.sessionStartedAt()).isEqualTo(now);
		assertThat(jwtProvider.getRole(issued.token())).isEqualTo("ADMIN");
	}

	@Test
	@DisplayName("연장해도 최초 로그인 시각은 그대로이고, 8시간을 넘지 못한다")
	void extensionIsCappedByAbsoluteLifetime() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		Instant startedAt = now.minus(Duration.ofHours(7)).minus(Duration.ofMinutes(50));
		String token = jwtProvider.createAdminSessionToken(userId, startedAt, now.plus(Duration.ofMinutes(5)));

		AdminSessionTokens.Issued extended = at(now).extend(token).orElseThrow();

		assertThat(extended.sessionStartedAt()).isEqualTo(startedAt);
		assertThat(extended.expiresAt()).isEqualTo(startedAt.plus(Duration.ofHours(8)));
		assertThat(extended.expiresInSeconds()).isEqualTo(600);
		assertThat(extended.extendable()).isFalse();
	}

	@Test
	@DisplayName("활동 중이면 만료를 지금부터 30분 뒤로 민다")
	void extensionSlidesIdleTimeout() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		Instant startedAt = now.minus(Duration.ofHours(1));
		String token = jwtProvider.createAdminSessionToken(userId, startedAt, now.plus(Duration.ofMinutes(3)));

		AdminSessionTokens.Issued extended = at(now).extend(token).orElseThrow();

		assertThat(extended.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
		assertThat(extended.extendable()).isTrue();
	}

	@Test
	@DisplayName("절대 수명이 끝났으면 연장하지 않는다")
	void refusesAfterAbsoluteLifetime() {
		Instant realNow = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		Instant startedAt = realNow.minus(Duration.ofHours(8)).plus(Duration.ofMinutes(1));
		String token = jwtProvider.createAdminSessionToken(userId, startedAt, realNow.plus(Duration.ofMinutes(1)));

		// 토큰은 아직 서명상 유효하지만, 시계가 절대 만료를 지났다.
		assertThat(at(realNow.plus(Duration.ofMinutes(2))).extend(token)).isEmpty();
	}

	@Test
	@DisplayName("일반 로그인 ADMIN 토큰은 관리자 세션이 아니다 — 연장 대상에서 빠진다")
	void plainAccessTokenIsNotAdminSession() {
		String plain = jwtProvider.createAccessToken(userId, "ADMIN");

		assertThat(jwtProvider.parseAdminSession(plain)).isEmpty();
		assertThat(at(Instant.now()).extend(plain)).isEmpty();
		assertThat(at(Instant.now()).status(plain)).isEmpty();
	}

	@Test
	@DisplayName("방금 발급한 토큰은 다시 만들지 않는다(1분 간격)")
	void doesNotReissueFreshToken() {
		Instant now = Instant.now();
		AdminSessionClaims claims = jwtProvider.parseAdminSession(at(now).issueNew(userId).token()).orElseThrow();

		assertThat(at(now.plusSeconds(30)).shouldSlide(claims)).isFalse();
		assertThat(at(now.plusSeconds(120)).shouldSlide(claims)).isTrue();
	}

	@Test
	@DisplayName("남은 시간 조회는 토큰을 바꾸지 않고 숫자만 계산한다")
	void statusDoesNotExtend() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		AdminSessionTokens.Issued issued = at(now).issueNew(userId);

		AdminSessionTokens.Status status = at(now.plus(Duration.ofMinutes(10))).status(issued.token()).orElseThrow();

		assertThat(status.remainingSeconds()).isEqualTo(Duration.ofMinutes(20).toSeconds());
		assertThat(status.sessionMaxRemainingSeconds()).isEqualTo(Duration.ofMinutes(470).toSeconds());
		assertThat(status.idleTimeoutSeconds()).isEqualTo(1800);
	}
}
