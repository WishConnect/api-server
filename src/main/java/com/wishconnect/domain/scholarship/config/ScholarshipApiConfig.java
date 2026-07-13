package com.wishconnect.domain.scholarship.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/*
장학금 공공데이터 API 호출에 사용할 RestClient 설정 클래스입니다.
외부 API 호출이 오래 멈추지 않도록 연결/읽기 타임아웃을 함께 설정합니다.
 */
@Configuration
@EnableConfigurationProperties(ScholarshipApiProperties.class)
public class ScholarshipApiConfig {

	@Bean
	RestClient scholarshipRestClient(ScholarshipApiProperties properties) {
		//RestClient가 HTTP 요청을 어떻게 보낼지 설정하는 객체
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(5));
		requestFactory.setReadTimeout(Duration.ofSeconds(15));

        //RestClient를 생성하고, baseUrl과 requestFactory를 설정
		return RestClient.builder()
			.baseUrl(properties.baseUrlOrDefault())
			.requestFactory(requestFactory)
			.build();
	}
}
