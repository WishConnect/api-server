package com.wishconnect.global.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS 허용 출처 설정. 환경별 yml 로 관리(로컬 테스트 프론트 등).
 *
 * @param allowedOrigins 허용할 출처 목록 (예: http://localhost:3000)
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
