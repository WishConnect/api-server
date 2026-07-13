package com.wishconnect.domain.scholarship.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/*
application-local.yml의 scholarship.api 설정값을 코드에서 사용하기 위한 설정 객체입니다.
API 주소, 인증키, 조회 페이지 크기, 조회할 엔드포인트 개수 같은 실행 옵션을 관리합니다.
 */
@ConfigurationProperties(prefix = "scholarship.api")
public record ScholarshipApiProperties(
	String baseUrl,
	String path,
	String docsUrl,
	String fallbackPath,
	String serviceKey,
	String source,
	String sourceUrl,
	Integer perPage,
	Integer endpointLimit
) {

	public String baseUrlOrDefault() {
		return StringUtils.hasText(baseUrl) ? baseUrl : "https://api.odcloud.kr/api";
	}

	public String docsUrlOrDefault() {
		return StringUtils.hasText(docsUrl)
			? docsUrl
			: "https://infuser.odcloud.kr/oas/docs?namespace=15028252/v1";
	}

	public String fallbackPathOrDefault() {
		return StringUtils.hasText(fallbackPath)
			? fallbackPath
			: "/15028252/v1/uddi:d25e6d10-d504-4ed3-b427-b0825ae8710d_202003201526";
	}

	public String sourceName() {
		return source == null || source.isBlank() ? "KOSAF_SCHOLARSHIP" : source;
	}

	public String requestPath() {
		return path == null || path.isBlank() ? "/" : path;
	}

	public int perPageOrDefault() {
		return perPage == null || perPage < 1 ? 1000 : perPage;
	}

	public int endpointLimitOrDefault() {
		return endpointLimit == null || endpointLimit < 0 ? 0 : endpointLimit;
	}

}
