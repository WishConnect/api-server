package com.wishconnect.domain.auth.client;

import com.wishconnect.domain.auth.client.dto.KakaoTokenResponse;
import com.wishconnect.domain.auth.client.dto.KakaoUserResponse;
import com.wishconnect.domain.auth.config.KakaoOAuthProperties;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 카카오 외부 API 통신 전담 (RestClient).
 * 인가코드 → access_token 교환, access_token → 사용자 정보 조회.
 */
@Slf4j
@Component
public class KakaoApiClient {

	private final KakaoOAuthProperties properties;
	private final RestClient restClient;

	public KakaoApiClient(KakaoOAuthProperties properties, RestClient.Builder restClientBuilder) {
		this.properties = properties;
		this.restClient = restClientBuilder.build();
	}

	/** 인가코드로 카카오 access_token 을 발급받는다. */
	public KakaoTokenResponse getToken(String code) {
		log.info("[Kakao] 토큰 발급 요청 시작");
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("client_id", properties.clientId());
		form.add("redirect_uri", properties.redirectUri());
		form.add("code", code);
		if (StringUtils.hasText(properties.clientSecret())) {
			form.add("client_secret", properties.clientSecret());
		}

		try {
			KakaoTokenResponse response = restClient.post()
					.uri(properties.tokenUri())
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(form)
					.retrieve()
					.body(KakaoTokenResponse.class);
			log.info("[Kakao] 토큰 발급 성공");
			return response;
		} catch (Exception e) {
			log.warn("[Kakao] 토큰 발급 실패: {}", e.getMessage());
			throw new CustomException(ErrorCode.KAKAO_TOKEN_FAILED);
		}
	}

	/** access_token 으로 카카오 사용자 정보를 조회한다. */
	public KakaoUserResponse getUserInfo(String kakaoAccessToken) {
		log.info("[Kakao] 사용자 정보 조회 시작");
		try {
			KakaoUserResponse response = restClient.get()
					.uri(properties.userInfoUri())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + kakaoAccessToken)
					.retrieve()
					.body(KakaoUserResponse.class);
			log.info("[Kakao] 사용자 정보 조회 성공 (kakaoId={})", response != null ? response.id() : null);
			return response;
		} catch (Exception e) {
			log.warn("[Kakao] 사용자 정보 조회 실패: {}", e.getMessage());
			throw new CustomException(ErrorCode.KAKAO_USER_INFO_FAILED);
		}
	}
}
