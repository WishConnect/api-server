package com.wishconnect.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 구글 OAuth 연동 설정. 민감정보(client-id/secret)는 yml + 환경변수로 주입.
 */
@ConfigurationProperties(prefix = "google")
public record GoogleOAuthProperties(
		String clientId,
		String clientSecret,
		String redirectUri,
		String tokenUri,
		String userInfoUri
) {
}
