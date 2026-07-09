package com.wishconnect.domain.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 네이버 토큰 발급 API 응답.
 */
public record NaverTokenResponse(
		@JsonProperty("access_token") String accessToken,
		@JsonProperty("token_type") String tokenType,
		@JsonProperty("refresh_token") String refreshToken,
		@JsonProperty("expires_in") String expiresIn,
		@JsonProperty("error") String error,
		@JsonProperty("error_description") String errorDescription
) {
}
