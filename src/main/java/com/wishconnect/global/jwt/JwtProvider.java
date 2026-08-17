package com.wishconnect.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 자체 JWT(Access/Refresh) 발급 및 검증 책임을 가진다. (HS256)
 */
@Slf4j
@Component
public class JwtProvider {

	private static final String ROLE_CLAIM = "role";

	private final SecretKey key;
	private final long accessTokenValidity;
	private final long refreshTokenValidity;

	public JwtProvider(JwtProperties properties) {
		this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
		this.accessTokenValidity = properties.accessTokenValidity();
		this.refreshTokenValidity = properties.refreshTokenValidity();
	}

	/**
	 * Access Token 발급. 권한 판정을 매 요청 DB 조회 없이 하기 위해 role 을 클레임에 담는다.
	 *
	 * @param role {@code UserRole} 이름(예: USER/ADMIN). global 이 domain 에 의존하지 않도록 문자열로 받는다.
	 */
	public String createAccessToken(UUID userId, String role) {
		return createToken(userId, accessTokenValidity, role);
	}

	/** Refresh Token 은 재발급 용도라 권한을 담지 않는다. */
	public String createRefreshToken(UUID userId) {
		return createToken(userId, refreshTokenValidity, null);
	}

	private String createToken(UUID userId, long validityMillis, String role) {
		Date now = new Date();
		Date expiry = new Date(now.getTime() + validityMillis);
		JwtBuilder builder = Jwts.builder()
				.subject(userId.toString())
				.issuedAt(now)
				.expiration(expiry);
		if (role != null) {
			builder.claim(ROLE_CLAIM, role);
		}
		return builder.signWith(key, Jwts.SIG.HS256).compact();
	}

	/**
	 * Access Token 의 권한 이름. role 클레임이 도입되기 전 발급된 토큰에는 값이 없으므로
	 * 그 경우 {@code null} 을 돌려주고, 호출측이 일반 사용자로 취급한다.
	 */
	public String getRole(String token) {
		Object role = parseClaims(token).get(ROLE_CLAIM);
		return role == null ? null : role.toString();
	}

	/** 서명/만료가 유효하면 true. */
	public boolean validateToken(String token) {
		try {
			parseClaims(token);
			return true;
		} catch (JwtException | IllegalArgumentException e) {
			log.warn("[JWT] 유효하지 않은 토큰: {}", e.getMessage());
			return false;
		}
	}

	public UUID getUserId(String token) {
		return UUID.fromString(parseClaims(token).getSubject());
	}

	public long getAccessTokenValidity() {
		return accessTokenValidity;
	}

	public long getRefreshTokenValidity() {
		return refreshTokenValidity;
	}

	private Claims parseClaims(String token) {
		return Jwts.parser()
				.verifyWith(key)
				.build()
				.parseSignedClaims(token)
				.getPayload();
	}
}
