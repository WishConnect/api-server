package com.wishconnect.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 클라이언트 빈. 자격증명은 기본 공급자 체인(EC2 IAM 역할 → 환경변수 → 프로파일)을 따른다.
 * 로컬에 자격증명이 없어도 빈 생성은 되고, 실제 업로드 시점에만 실패한다(업로드는 실패 격리 처리).
 */
@Configuration
public class S3Config {

	@Bean
	public S3Client s3Client(@Value("${app.s3.region:ap-northeast-2}") String region) {
		return S3Client.builder()
				.region(Region.of(region))
				.build();
	}

	@Bean
	public S3Presigner s3Presigner(@Value("${app.s3.region:ap-northeast-2}") String region) {
		return S3Presigner.builder()
				.region(Region.of(region))
				.build();
	}
}
