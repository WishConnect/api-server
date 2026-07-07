package com.wishconnect.global.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 서명/만료 설정. yml + 환경변수로 주입한다(하드코딩 금지).
 *
 * @param secret                 HS256 서명 비밀키 (최소 256bit)
 * @param accessTokenValidity    Access Token 유효시간(ms)
 * @param refreshTokenValidity   Refresh Token 유효시간(ms)
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
		String secret,
		long accessTokenValidity,
		long refreshTokenValidity
) {
}
