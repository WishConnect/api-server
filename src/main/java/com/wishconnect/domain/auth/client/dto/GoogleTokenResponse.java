package com.wishconnect.domain.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 구글 토큰 발급 API 응답.
 */
public record GoogleTokenResponse(
		@JsonProperty("access_token") String accessToken,
		@JsonProperty("token_type") String tokenType,
		@JsonProperty("id_token") String idToken,
		@JsonProperty("expires_in") Integer expiresIn,
		@JsonProperty("scope") String scope
) {
}
