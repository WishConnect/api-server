package com.wishconnect.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카카오 OAuth 연동 설정. REST API 키/Client Secret/Redirect URI 는
 * yml + 환경변수로 주입하며 하드코딩하지 않는다.
 *
 * @param clientId     카카오 REST API 키
 * @param clientSecret 카카오 Client Secret (선택)
 * @param redirectUri  Redirect URI (환경별 분리)
 * @param tokenUri     카카오 토큰 발급 엔드포인트
 * @param userInfoUri  카카오 사용자 정보 엔드포인트
 */
@ConfigurationProperties(prefix = "kakao")
public record KakaoOAuthProperties(
		String clientId,
		String clientSecret,
		String redirectUri,
		String tokenUri,
		String userInfoUri
) {
}
