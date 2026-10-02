package com.wishconnect.global.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 관리자 콘솔 보안 설정. 값이 없으면 기본값을 쓴다(운영 환경변수로 조정 가능).
 *
 * @param loginMaxFailures   loginId 기준 연속 실패 허용 횟수. 이 횟수에 도달하면 잠근다
 * @param loginLockDuration  loginId 잠금 시간
 * @param ipMaxFailures      IP 기준 보조 제한. 창(window) 안에서 이 횟수에 도달하면 그 IP 를 잠근다
 * @param ipWindow           IP 실패를 세는 창이자 잠금 시간
 * @param sessionIdleTimeout 관리자 세션의 유휴 만료. 활동이 있으면 이만큼 다시 늘어난다
 * @param sessionMaxLifetime 최초 로그인부터의 절대 수명. 연장해도 이 시각을 넘지 않는다
 */
@ConfigurationProperties(prefix = "admin.security")
public record AdminSecurityProperties(
		@DefaultValue("5") int loginMaxFailures,
		@DefaultValue("15m") Duration loginLockDuration,
		@DefaultValue("20") int ipMaxFailures,
		@DefaultValue("15m") Duration ipWindow,
		@DefaultValue("30m") Duration sessionIdleTimeout,
		@DefaultValue("8h") Duration sessionMaxLifetime
) {
}
