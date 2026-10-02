package com.wishconnect.global.jwt;

import com.wishconnect.global.config.AdminSecurityProperties;
import com.wishconnect.global.jwt.JwtProvider.AdminSessionClaims;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 관리자 세션 토큰 발급·연장.
 *
 * <h2>방식: 슬라이딩 + 절대 수명</h2>
 * <ul>
 *   <li>토큰 만료 = min(지금 + 유휴 만료(기본 30분), 최초 로그인 + 절대 수명(기본 8시간))</li>
 *   <li>관리자가 API 를 쓰면(활동) {@code AdminSessionSlidingFilter} 가 새 토큰을 응답 헤더와 쿠키로 내려
 *       만료를 다시 30분 뒤로 민다. 연장 버튼은 {@code POST /api/v1/admin/auth/extend} 로 같은 일을 한다.</li>
 *   <li>최초 로그인 시각은 토큰에 담겨 연장해도 바뀌지 않는다. 그래서 8시간이 지나면 무조건 다시 로그인한다.</li>
 * </ul>
 *
 * <p>서버에 세션을 저장하지 않는다(기존 Access Token 구조 유지). 대신 로그아웃해도 이미 나간 토큰은
 * 만료까지 유효하다 — 기존과 같은 한계다.
 */
@Component
public class AdminSessionTokens {

	/** 이보다 최근에 발급된 토큰은 다시 발급하지 않는다. 요청마다 새 토큰을 만드는 낭비를 막는다. */
	static final Duration REISSUE_INTERVAL = Duration.ofSeconds(60);

	private final JwtProvider jwtProvider;
	private final AdminSecurityProperties properties;
	private final Clock clock;

	@Autowired
	public AdminSessionTokens(JwtProvider jwtProvider, AdminSecurityProperties properties) {
		this(jwtProvider, properties, Clock.systemUTC());
	}

	AdminSessionTokens(JwtProvider jwtProvider, AdminSecurityProperties properties, Clock clock) {
		this.jwtProvider = jwtProvider;
		this.properties = properties;
		this.clock = clock;
	}

	/** 로그인 직후 새 세션을 연다. */
	public Issued issueNew(UUID userId) {
		Instant now = clock.instant();
		return issue(userId, now, now);
	}

	/**
	 * 같은 세션으로 만료를 다시 민다. 절대 수명이 이미 끝났으면 empty.
	 *
	 * @param token 현재 유효한 관리자 세션 토큰
	 */
	public Optional<Issued> extend(String token) {
		return jwtProvider.parseAdminSession(token).flatMap(claims -> {
			Instant now = clock.instant();
			if (!now.isBefore(maxExpiresAt(claims.sessionStartedAt()))) {
				return Optional.empty();
			}
			return Optional.of(issue(claims.userId(), claims.sessionStartedAt(), now));
		});
	}

	/**
	 * 활동으로 연장할 만한 토큰인지. 방금 발급된 토큰이거나 이미 절대 수명에 닿은 토큰은 다시 만들 필요가 없다.
	 */
	public boolean shouldSlide(AdminSessionClaims claims) {
		Instant now = clock.instant();
		Instant cap = maxExpiresAt(claims.sessionStartedAt());
		return claims.issuedAt().plus(REISSUE_INTERVAL).isBefore(now) && claims.expiresAt().isBefore(cap);
	}

	/** 연장하지 않고 현재 상태만 계산한다(남은 시간 조회용). */
	public Optional<Status> status(String token) {
		return jwtProvider.parseAdminSession(token).map(claims -> {
			Instant now = clock.instant();
			Instant cap = maxExpiresAt(claims.sessionStartedAt());
			return new Status(claims.expiresAt(), seconds(now, claims.expiresAt()), claims.sessionStartedAt(), cap,
					seconds(now, cap), claims.expiresAt().isBefore(cap), properties.sessionIdleTimeout().toSeconds());
		});
	}

	public long idleTimeoutSeconds() {
		return properties.sessionIdleTimeout().toSeconds();
	}

	public Optional<AdminSessionClaims> parse(String token) {
		return jwtProvider.parseAdminSession(token);
	}

	private Issued issue(UUID userId, Instant sessionStartedAt, Instant now) {
		Instant cap = maxExpiresAt(sessionStartedAt);
		Instant idle = now.plus(properties.sessionIdleTimeout());
		Instant expiresAt = idle.isBefore(cap) ? idle : cap;
		String token = jwtProvider.createAdminSessionToken(userId, sessionStartedAt, expiresAt);
		return new Issued(token, expiresAt, seconds(now, expiresAt), sessionStartedAt, cap, seconds(now, cap),
				expiresAt.isBefore(cap));
	}

	private Instant maxExpiresAt(Instant sessionStartedAt) {
		return sessionStartedAt.plus(properties.sessionMaxLifetime());
	}

	private static long seconds(Instant from, Instant to) {
		return Math.max(Duration.between(from, to).getSeconds(), 0);
	}

	/**
	 * 새로 발급한 토큰.
	 *
	 * @param extendable 다음 연장으로 만료가 더 늘어날 수 있는지. 절대 수명에 닿았으면 false
	 */
	public record Issued(String token, Instant expiresAt, long expiresInSeconds, Instant sessionStartedAt,
			Instant sessionMaxExpiresAt, long sessionMaxRemainingSeconds, boolean extendable) {
	}

	/** 현재 세션 상태. */
	public record Status(Instant expiresAt, long remainingSeconds, Instant sessionStartedAt,
			Instant sessionMaxExpiresAt, long sessionMaxRemainingSeconds, boolean extendable,
			long idleTimeoutSeconds) {
	}
}
