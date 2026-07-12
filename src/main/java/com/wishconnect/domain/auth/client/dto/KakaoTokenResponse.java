package com.wishconnect.domain.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 토큰 발급 API 응답.
 */
public record KakaoTokenResponse(
		@JsonProperty("access_token") String accessToken,
		@JsonProperty("token_type") String tokenType,
		@JsonProperty("refresh_token") String refreshToken,
		@JsonProperty("expires_in") Integer expiresIn,
		@JsonProperty("scope") String scope
) {
}
