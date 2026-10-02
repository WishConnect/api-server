package com.wishconnect.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 로그인 실패 응답의 data. 화면이 "n회 실패, 5회 실패 시 15분간 잠김" 을 그리는 데 쓴다.
 *
 * <p>계정이 있든 없든 같은 규칙으로 세고 같은 형태로 내린다. 응답 차이로 계정 존재 여부를 알 수 없어야 한다.
 */
@Schema(description = "관리자 로그인 실패·잠금 정보")
public record AdminLoginAttemptResponse(
		@Schema(description = "현재 연속 실패 횟수(잠금 창 안)") int failedCount,
		@Schema(description = "잠금 기준 횟수") int maxFailures,
		@Schema(description = "잠기기 전까지 남은 시도 횟수") int remainingAttempts,
		@Schema(description = "잠금 시간(분)") long lockMinutes,
		@Schema(description = "잠겼는지") boolean locked,
		@Schema(description = "잠금 사유. ACCOUNT=아이디 기준, IP=같은 IP 의 실패 누적, 잠기지 않았으면 null")
		String lockScope,
		@Schema(description = "잠금이 풀리기까지 남은 초. 잠기지 않았으면 0") long lockRemainingSeconds
) {
}
