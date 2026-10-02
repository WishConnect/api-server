package com.wishconnect.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "관리자 로그인 결과")
public record AdminLoginResponse(
		@Schema(description = "관리자 API 호출용 Access Token") String accessToken,
		@Schema(description = "Access Token 만료까지 남은 초") long expiresInSeconds,
		@Schema(description = "관리자 이름") String name,
		@Schema(description = "Access Token 만료 시각(UTC). 활동하면 뒤로 밀린다") Instant expiresAt,
		@Schema(description = "세션 절대 만료 시각(UTC). 최초 로그인 + 8시간, 연장해도 넘지 않는다")
		Instant sessionMaxExpiresAt
) {

	public AdminLoginResponse(String accessToken, long expiresInSeconds, String name) {
		this(accessToken, expiresInSeconds, name, null, null);
	}
}
