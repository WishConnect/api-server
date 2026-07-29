package com.wishconnect.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * app.cors.allowed-origins 바인딩과 출처 매칭 검증.
 *
 * <p>운영에서 scheme 없는 값(wish-connect.com)이 들어가 preflight 가 403 이 된 이력이 있어,
 * 콤마 구분 다중 출처 바인딩과 scheme 포함 여부를 테스트로 고정한다.
 */
class CorsConfigTest {

	private static final String PROD_ORIGINS =
			"app.cors.allowed-origins=https://wish-connect.com,https://www.wish-connect.com";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfig.class, CorsConfig.class);

	@DisplayName("콤마로 구분한 여러 출처가 각각의 항목으로 바인딩된다")
	@Test
	void bindsCommaSeparatedOrigins() {
		runner.withPropertyValues(PROD_ORIGINS).run(context -> {
			CorsConfiguration config = corsConfiguration(context.getBean(CorsConfigurationSource.class));

			assertThat(config.getAllowedOrigins())
					.containsExactly("https://wish-connect.com", "https://www.wish-connect.com");
		});
	}

	@DisplayName("허용 출처와 정확히 일치하는 Origin 만 통과한다")
	@Test
	void matchesOnlyConfiguredOrigins() {
		runner.withPropertyValues(PROD_ORIGINS).run(context -> {
			CorsConfiguration config = corsConfiguration(context.getBean(CorsConfigurationSource.class));

			assertThat(config.checkOrigin("https://wish-connect.com")).isNotNull();
			assertThat(config.checkOrigin("https://www.wish-connect.com")).isNotNull();
			// scheme 누락 값은 매칭되지 않는다 (운영 preflight 403 의 원인)
			assertThat(config.checkOrigin("wish-connect.com")).isNull();
			assertThat(config.checkOrigin("https://evil.com")).isNull();
		});
	}

	@DisplayName("자격증명을 허용하므로 출처가 와일드카드로 열려서는 안 된다")
	@Test
	void doesNotAllowWildcardWithCredentials() {
		runner.withPropertyValues("app.cors.allowed-origins=https://wish-connect.com").run(context -> {
			CorsConfiguration config = corsConfiguration(context.getBean(CorsConfigurationSource.class));

			assertThat(config.getAllowCredentials()).isTrue();
			assertThat(config.getAllowedOrigins()).doesNotContain(CorsConfiguration.ALL);
		});
	}

	private CorsConfiguration corsConfiguration(CorsConfigurationSource source) {
		HttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/auth/login");
		return source.getCorsConfiguration(request);
	}

	@Configuration
	@EnableConfigurationProperties(CorsProperties.class)
	static class PropertiesConfig {
	}
}
