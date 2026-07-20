package com.wishconnect.domain.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/*
대학알리미/공공데이터 기반 학교·학과 마스터 데이터 동기화 설정입니다.
실제 API 명세의 서비스명/operation명이 바뀔 수 있어 URL은 yml에서 교체 가능하게 둡니다.
 */
@ConfigurationProperties(prefix = "academic-info.api")
public record AcademicInfoApiProperties(
		String serviceKey,
		String schoolBaseUrl,
		String schoolPath,
		String majorBaseUrl,
		String majorPath,
		Integer pageSize,
		Integer maxPages,
		String surveyYear
) {

	public String schoolBaseUrlOrDefault() {
		return StringUtils.hasText(schoolBaseUrl)
				? schoolBaseUrl
				: "https://apis.data.go.kr/B340014/BasicInformationService_2";
	}

	public String schoolPathOrDefault() {
		return StringUtils.hasText(schoolPath) ? schoolPath : "/getUniversityCode";
	}

	public String majorBaseUrlOrDefault() {
		return StringUtils.hasText(majorBaseUrl)
				? majorBaseUrl
				: "https://apis.data.go.kr/B340014/BasicInformationService_1";
	}

	public String majorPathOrDefault() {
		return StringUtils.hasText(majorPath) ? majorPath : "/getUniversityMajorCode";
	}

	public int pageSizeOrDefault() {
		return pageSize == null || pageSize < 1 ? 1000 : pageSize;
	}

	public int maxPagesOrDefault() {
		return maxPages == null || maxPages < 1 ? 100 : maxPages;
	}
}
