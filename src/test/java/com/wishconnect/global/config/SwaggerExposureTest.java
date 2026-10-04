package com.wishconnect.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 운영 프로필에서 Swagger 가 기본으로 꺼지는지 확인한다.
 *
 * <p>빈은 하나도 띄우지 않고 application.yml + application-{profile}.yml 병합 결과만 읽는다.
 * 테스트 클래스패스에는 별도 application.yml(src/test/resources)이 있어 main 설정을 가리므로,
 * 실제 배포되는 src/main/resources 파일을 직접 지정한다.
 */
@DisplayName("Swagger 노출 설정")
class SwaggerExposureTest {

	private static final String MAIN_CONFIG = "--spring.config.location=file:src/main/resources/";

	@Configuration
	static class Empty {
	}

	@Test
	@DisplayName("prod 프로필은 SWAGGER_ENABLED 가 없어도 문서를 끈다")
	void disabledInProdByDefault() {
		try (ConfigurableApplicationContext ctx = run("prod")) {
			assertThat(enabled(ctx.getEnvironment(), "springdoc.api-docs.enabled")).isFalse();
			assertThat(enabled(ctx.getEnvironment(), "springdoc.swagger-ui.enabled")).isFalse();
		}
	}

	@Test
	@DisplayName("prod 에서도 SWAGGER_ENABLED=true 로 명시하면 켤 수 있다")
	void canBeEnabledExplicitly() {
		try (ConfigurableApplicationContext ctx = run("prod", "--SWAGGER_ENABLED=true")) {
			assertThat(enabled(ctx.getEnvironment(), "springdoc.api-docs.enabled")).isTrue();
		}
	}

	@Test
	@DisplayName("로컬 프로필은 기존대로 켜져 있다")
	void enabledLocally() {
		try (ConfigurableApplicationContext ctx = run("local")) {
			assertThat(enabled(ctx.getEnvironment(), "springdoc.api-docs.enabled")).isTrue();
			assertThat(enabled(ctx.getEnvironment(), "springdoc.swagger-ui.enabled")).isTrue();
		}
	}

	private ConfigurableApplicationContext run(String profile, String... args) {
		return new SpringApplicationBuilder(Empty.class)
				.web(WebApplicationType.NONE)
				.profiles(profile)
				.logStartupInfo(false)
				.run(withMainConfig(args));
	}

	private String[] withMainConfig(String[] args) {
		String[] all = java.util.Arrays.copyOf(args, args.length + 1);
		all[args.length] = MAIN_CONFIG;
		return all;
	}

	private Boolean enabled(Environment env, String key) {
		return env.getProperty(key, Boolean.class);
	}
}
