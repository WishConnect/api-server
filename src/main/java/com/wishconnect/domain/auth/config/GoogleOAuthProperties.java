package com.wishconnect.domain.auth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 구글 OAuth 연동 설정. 민감정보(client-id/secret)는 yml + 환경변수로 주입.
 *
 * @param redirectUri         기본 Redirect URI. 프론트가 값을 보내지 않으면 이 값을 쓴다.
 * @param allowedRedirectUris 프론트가 보낼 수 있는 Redirect URI 허용목록(콤마 구분). 로컬 개발용 등.
 */
@ConfigurationProperties(prefix = "google")
public record GoogleOAuthProperties(
		String clientId,
		String clientSecret,
		String redirectUri,
		List<String> allowedRedirectUris,
		String tokenUri,
		String userInfoUri
) {

	/**
	 * 프론트가 보낸 redirectUri 를 검증해 토큰 교환에 쓸 값을 고른다.
	 *
	 * @return 사용할 redirect_uri. 허용목록에 없으면 {@code null}
	 */
	public String resolveRedirectUri(String requested) {
		return RedirectUriPolicy.resolve(requested, redirectUri, allowedRedirectUris);
	}
}
