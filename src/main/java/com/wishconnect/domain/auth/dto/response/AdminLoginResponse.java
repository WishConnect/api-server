package com.wishconnect.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 로그인 결과")
public record AdminLoginResponse(
		@Schema(description = "관리자 API 호출용 Access Token") String accessToken,
		@Schema(description = "Access Token 만료까지 남은 초") long expiresInSeconds,
		@Schema(description = "관리자 이름") String name
) {
}
