package com.wishconnect.domain.auth.client.dto;

/**
 * 구글 사용자 정보 API(oauth2/v3/userinfo) 응답. providerId 는 {@code sub}.
 */
public record GoogleUserResponse(
		String sub,
		String email,
		String name
) {
}
