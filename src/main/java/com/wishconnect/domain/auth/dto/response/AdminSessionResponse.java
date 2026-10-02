package com.wishconnect.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 관리자 세션 상태. 연장 API 는 새 토큰을 함께 주고, 조회 API 는 accessToken 이 null 이다.
 */
@Schema(description = "관리자 세션 상태")
public record AdminSessionResponse(
		@Schema(description = "새 Access Token. 연장 응답에만 있다. 이후 요청의 Authorization 에 이 값을 쓴다")
		String accessToken,
		@Schema(description = "Access Token 만료 시각(UTC)") Instant expiresAt,
		@Schema(description = "만료까지 남은 초") long remainingSeconds,
		@Schema(description = "최초 로그인 시각(UTC)") Instant sessionStartedAt,
		@Schema(description = "절대 만료 시각(UTC). 연장해도 넘지 않는다") Instant sessionMaxExpiresAt,
		@Schema(description = "절대 만료까지 남은 초") long sessionMaxRemainingSeconds,
		@Schema(description = "연장하면 만료가 더 늘어나는지. 절대 만료에 닿았으면 false") boolean extendable,
		@Schema(description = "활동 1회로 늘어나는 유휴 만료(초)") long idleTimeoutSeconds
) {
}
