package com.wishconnect.domain.application.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Anthropic Claude SDK 클라이언트 빈 등록.
 * <p>
 * API 키는 {@code ANTHROPIC_API_KEY} 환경변수로 주입한다.
 * 환경변수가 설정되지 않으면 스텁 키로 빈을 생성하고, 실제 호출 시점에 SDK가 인증 오류를 반환한다.
 * 이렇게 하면 AI 파트를 사용하지 않는 팀원도 앱을 정상적으로 기동할 수 있다.
 */
@Slf4j
@Configuration
public class LlmConfig {

	private static final String API_KEY_ENV = "ANTHROPIC_API_KEY";

	@Bean
	public AnthropicClient anthropicClient() {
		String apiKey = System.getenv(API_KEY_ENV);
		if (apiKey == null || apiKey.isBlank()) {
			log.warn("환경변수 {} 가 설정되지 않았습니다. LLM 호출 시 인증 오류가 발생합니다.", API_KEY_ENV);
			return AnthropicOkHttpClient.builder()
					.apiKey("anthropic-api-key-not-set")
					.build();
		}
		return AnthropicOkHttpClient.builder()
				.apiKey(apiKey)
				.build();
	}
}
