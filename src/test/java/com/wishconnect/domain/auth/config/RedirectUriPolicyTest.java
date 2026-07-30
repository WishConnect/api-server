package com.wishconnect.domain.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 소셜 로그인 redirect_uri 허용목록 검증.
 *
 * <p>로컬 개발(localhost)과 운영 도메인이 달라 프론트가 값을 전달해야 하는데,
 * 임의의 값을 그대로 쓰면 안 되므로 허용목록 밖은 거부해야 한다.
 */
class RedirectUriPolicyTest {

	private static final String PROD = "https://wish-connect.com/auth/kakao/callback";
	private static final String LOCAL = "http://localhost:3000/auth/kakao/callback";

	private final KakaoOAuthProperties properties = new KakaoOAuthProperties(
			"client-id", "client-secret", PROD, List.of(LOCAL), "token-uri", "user-info-uri");

	@DisplayName("값을 보내지 않으면 설정된 기본값(운영 도메인)을 쓴다")
	@Test
	void fallsBackToConfiguredDefault() {
		assertThat(properties.resolveRedirectUri(null)).isEqualTo(PROD);
		assertThat(properties.resolveRedirectUri("  ")).isEqualTo(PROD);
	}

	@DisplayName("허용목록에 있는 로컬 개발용 값은 그대로 사용한다")
	@Test
	void allowsWhitelistedLocalUri() {
		assertThat(properties.resolveRedirectUri(LOCAL)).isEqualTo(LOCAL);
	}

	@DisplayName("기본값 자체도 명시적으로 보낼 수 있다")
	@Test
	void allowsConfiguredDefaultExplicitly() {
		assertThat(properties.resolveRedirectUri(PROD)).isEqualTo(PROD);
	}

	@DisplayName("허용목록에 없는 값은 거부한다(null 반환 -> 호출측에서 400)")
	@Test
	void rejectsUnknownUri() {
		assertThat(properties.resolveRedirectUri("https://evil.com/steal")).isNull();
		// 도메인만 비슷한 경우도 통과시키지 않는다
		assertThat(properties.resolveRedirectUri("https://wish-connect.com.evil.com/auth/kakao/callback")).isNull();
		// 경로가 다르면 다른 값이다
		assertThat(properties.resolveRedirectUri("https://wish-connect.com/auth/kakao/callback2")).isNull();
	}

	@DisplayName("허용목록이 비어 있어도 기본값은 동작한다")
	@Test
	void worksWithoutAllowList() {
		KakaoOAuthProperties noList = new KakaoOAuthProperties(
				"client-id", null, PROD, null, "token-uri", "user-info-uri");

		assertThat(noList.resolveRedirectUri(null)).isEqualTo(PROD);
		assertThat(noList.resolveRedirectUri(LOCAL)).isNull();
	}
}
