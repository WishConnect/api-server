package com.wishconnect.global.jwt;

import io.jsonwebtoken.Claims;
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

	private final SecretKey key;
	private final long accessTokenValidity;
	private final long refreshTokenValidity;

	public JwtProvider(JwtProperties properties) {
		this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
		this.accessTokenValidity = properties.accessTokenValidity();
		this.refreshTokenValidity = properties.refreshTokenValidity();
	}

	public String createAccessToken(UUID userId) {
		return createToken(userId, accessTokenValidity);
	}

	public String createRefreshToken(UUID userId) {
		return createToken(userId, refreshTokenValidity);
	}

	private String createToken(UUID userId, long validityMillis) {
		Date now = new Date();
		Date expiry = new Date(now.getTime() + validityMillis);
		return Jwts.builder()
				.subject(userId.toString())
				.issuedAt(now)
				.expiration(expiry)
				.signWith(key, Jwts.SIG.HS256)
				.compact();
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
