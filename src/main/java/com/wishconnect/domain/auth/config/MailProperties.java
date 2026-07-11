package com.wishconnect.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이메일 발신 설정. 발신 주소는 yml + 환경변수로 주입(하드코딩 금지).
 *
 * @param from 발신 주소 (예: no-reply@wishconnect.kr)
 */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(String from) {
}
