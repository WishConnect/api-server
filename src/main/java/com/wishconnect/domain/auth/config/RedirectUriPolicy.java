package com.wishconnect.domain.auth.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 소셜 로그인 redirect_uri 결정 규칙. 제공자별 설정에서 공통으로 사용한다.
 *
 * <p>OAuth 토큰 교환의 {@code redirect_uri} 는 인가코드를 받을 때 사용한 값과
 * 반드시 같아야 한다. 로컬 개발(localhost)과 운영 도메인이 다르므로 프론트가
 * 자신이 사용한 값을 전달할 수 있게 하되, 임의의 값을 그대로 신뢰하지 않고
 * 허용목록과 대조한다.
 */
final class RedirectUriPolicy {

	private RedirectUriPolicy() {
	}

	/**
	 * @param requested   프론트가 요청 바디로 보낸 값(없을 수 있음)
	 * @param defaultUri  설정된 기본 Redirect URI
	 * @param allowedUris 추가 허용목록
	 * @return 사용할 redirect_uri. 요청값이 허용목록에 없으면 {@code null}
	 */
	static String resolve(String requested, String defaultUri, List<String> allowedUris) {
		if (!StringUtils.hasText(requested)) {
			return defaultUri;
		}
		String normalized = requested.trim();
		return allowed(defaultUri, allowedUris).contains(normalized) ? normalized : null;
	}

	private static List<String> allowed(String defaultUri, List<String> allowedUris) {
		List<String> uris = new ArrayList<>();
		if (StringUtils.hasText(defaultUri)) {
			uris.add(defaultUri.trim());
		}
		if (allowedUris != null) {
			allowedUris.stream()
					.filter(StringUtils::hasText)
					.map(String::trim)
					.forEach(uris::add);
		}
		return uris;
	}
}
