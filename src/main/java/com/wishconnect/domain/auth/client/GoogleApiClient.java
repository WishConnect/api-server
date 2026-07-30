package com.wishconnect.domain.auth.client;

import com.wishconnect.domain.auth.client.dto.GoogleTokenResponse;
import com.wishconnect.domain.auth.client.dto.GoogleUserResponse;
import com.wishconnect.domain.auth.config.GoogleOAuthProperties;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 구글 외부 API 통신 전담 (RestClient). 인가코드 → access_token → 사용자 정보 조회.
 */
@Slf4j
@Component
public class GoogleApiClient {

	private final GoogleOAuthProperties properties;
	private final RestClient restClient;

	public GoogleApiClient(GoogleOAuthProperties properties, RestClient.Builder restClientBuilder) {
		this.properties = properties;
		this.restClient = restClientBuilder.build();
	}

	/**
	 * 인가코드로 구글 access_token 을 발급받는다.
	 *
	 * @param redirectUri 프론트가 인가코드를 받을 때 사용한 값. 비우면 설정 기본값을 쓴다.
	 */
	public GoogleTokenResponse getToken(String code, String redirectUri) {
		String resolvedRedirectUri = properties.resolveRedirectUri(redirectUri);
		if (resolvedRedirectUri == null) {
			log.warn("[Google] 허용되지 않은 redirectUri 요청");
			throw new CustomException(ErrorCode.INVALID_REDIRECT_URI);
		}
		log.info("[Google] 토큰 발급 요청 시작");
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("client_id", properties.clientId());
		form.add("client_secret", properties.clientSecret());
		form.add("redirect_uri", resolvedRedirectUri);
		form.add("code", code);
		try {
			GoogleTokenResponse response = restClient.post()
					.uri(properties.tokenUri())
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(form)
					.retrieve()
					.body(GoogleTokenResponse.class);
			log.info("[Google] 토큰 발급 성공");
			return response;
		} catch (Exception e) {
			log.warn("[Google] 토큰 발급 실패: {}", e.getMessage());
			throw new CustomException(ErrorCode.GOOGLE_TOKEN_FAILED);
		}
	}

	public GoogleUserResponse getUserInfo(String accessToken) {
		log.info("[Google] 사용자 정보 조회 시작");
		try {
			GoogleUserResponse response = restClient.get()
					.uri(properties.userInfoUri())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
					.retrieve()
					.body(GoogleUserResponse.class);
			log.info("[Google] 사용자 정보 조회 성공");
			return response;
		} catch (Exception e) {
			log.warn("[Google] 사용자 정보 조회 실패: {}", e.getMessage());
			throw new CustomException(ErrorCode.GOOGLE_USER_INFO_FAILED);
		}
	}
}
