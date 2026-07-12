package com.wishconnect.domain.auth.client;

import com.wishconnect.domain.auth.client.dto.NaverTokenResponse;
import com.wishconnect.domain.auth.client.dto.NaverUserResponse;
import com.wishconnect.domain.auth.config.NaverOAuthProperties;
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
 * 네이버 외부 API 통신 전담 (RestClient). 인가코드(+state) → access_token → 사용자 정보 조회.
 */
@Slf4j
@Component
public class NaverApiClient {

	private final NaverOAuthProperties properties;
	private final RestClient restClient;

	public NaverApiClient(NaverOAuthProperties properties, RestClient.Builder restClientBuilder) {
		this.properties = properties;
		this.restClient = restClientBuilder.build();
	}

	public NaverTokenResponse getToken(String code, String state) {
		log.info("[Naver] 토큰 발급 요청 시작");
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("client_id", properties.clientId());
		form.add("client_secret", properties.clientSecret());
		form.add("code", code);
		form.add("state", state);
		try {
			NaverTokenResponse response = restClient.post()
					.uri(properties.tokenUri())
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(form)
					.retrieve()
					.body(NaverTokenResponse.class);
			if (response == null || !StringUtils.hasText(response.accessToken())) {
				throw new CustomException(ErrorCode.NAVER_TOKEN_FAILED);
			}
			log.info("[Naver] 토큰 발급 성공");
			return response;
		} catch (CustomException e) {
			throw e;
		} catch (Exception e) {
			log.warn("[Naver] 토큰 발급 실패: {}", e.getMessage());
			throw new CustomException(ErrorCode.NAVER_TOKEN_FAILED);
		}
	}

	public NaverUserResponse getUserInfo(String accessToken) {
		log.info("[Naver] 사용자 정보 조회 시작");
		try {
			NaverUserResponse response = restClient.get()
					.uri(properties.userInfoUri())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
					.retrieve()
					.body(NaverUserResponse.class);
			if (response == null || response.id() == null) {
				throw new CustomException(ErrorCode.NAVER_USER_INFO_FAILED);
			}
			log.info("[Naver] 사용자 정보 조회 성공");
			return response;
		} catch (CustomException e) {
			throw e;
		} catch (Exception e) {
			log.warn("[Naver] 사용자 정보 조회 실패: {}", e.getMessage());
			throw new CustomException(ErrorCode.NAVER_USER_INFO_FAILED);
		}
	}
}
