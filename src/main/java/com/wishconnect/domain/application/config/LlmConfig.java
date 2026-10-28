package com.wishconnect.domain.application.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Anthropic Claude SDK 클라이언트 빈 등록.
 * <p>
 * 키를 용도별로 나눈다. 사용량·비용을 따로 보고, 한쪽 키가 만료되거나 막혀도 다른 쪽은 계속 돌게 하기 위해서다.
 * <ul>
 *   <li>{@code ANTHROPIC_ESSAY_API_KEY}: 자기소개서·인터뷰 등 사용자 요청</li>
 *   <li>{@code ANTHROPIC_PARSER_API_KEY}: 공지 파싱·조건 추출·중복 판정·요약 등 데이터 처리</li>
 * </ul>
 * 용도별 키가 비어 있으면 {@code ANTHROPIC_API_KEY} 를 쓴다. 그것도 없으면 스텁 키로 빈을 만들고,
 * 실제 호출 시점에 SDK가 인증 오류를 반환한다. AI 파트를 쓰지 않는 팀원도 앱을 기동할 수 있게 하기 위해서다.
 */
@Slf4j
@Configuration
public class LlmConfig {

	static final String ESSAY_KEY_ENV = "ANTHROPIC_ESSAY_API_KEY";
	static final String PARSER_KEY_ENV = "ANTHROPIC_PARSER_API_KEY";
	static final String SHARED_KEY_ENV = "ANTHROPIC_API_KEY";

	@Bean
	public AnthropicClients anthropicClients() {
		return new AnthropicClients(
				build("자소서", ESSAY_KEY_ENV),
				build("파싱", PARSER_KEY_ENV));
	}

	private AnthropicClient build(String purpose, String keyEnv) {
		String apiKey = System.getenv(keyEnv);
		String source = keyEnv;
		if (isBlank(apiKey)) {
			apiKey = System.getenv(SHARED_KEY_ENV);
			source = SHARED_KEY_ENV;
		}
		if (isBlank(apiKey)) {
			log.warn("[LLM] {} 키가 없습니다({} / {} 모두 비어 있음). 호출 시 인증 오류가 발생합니다.",
					purpose, keyEnv, SHARED_KEY_ENV);
			return AnthropicOkHttpClient.builder()
					.apiKey("anthropic-api-key-not-set")
					.build();
		}
		// 키 값은 남기지 않는다. 어느 변수에서 읽었는지만 남겨 대체 키로 돌고 있는지 알 수 있게 한다.
		log.info("[LLM] {} 키를 {} 에서 읽었습니다.", purpose, source);
		return AnthropicOkHttpClient.builder()
				.apiKey(apiKey)
				.build();
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/** 용도별 Anthropic 클라이언트 묶음. */
	public record AnthropicClients(AnthropicClient essay, AnthropicClient parser) {
	}
}
