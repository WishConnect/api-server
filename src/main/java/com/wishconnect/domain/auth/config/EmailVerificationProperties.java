package com.wishconnect.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이메일 인증 코드 정책.
 *
 * @param codeTtlSeconds     인증 코드 유효시간(초, 기본 300=5분)
 * @param verifiedTtlSeconds 인증 완료 상태 유지시간(초, 회원가입까지 유효)
 * @param cooldownSeconds    재발송 제한 간격(초)
 */
@ConfigurationProperties(prefix = "app.email-verification")
public record EmailVerificationProperties(
		long codeTtlSeconds,
		long verifiedTtlSeconds,
		long cooldownSeconds
) {
}
