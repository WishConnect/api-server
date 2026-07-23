package com.wishconnect.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI) 문서 설정.
 *
 * <p>문서 UI: {@code /swagger-ui.html}, 스펙: {@code /v3/api-docs}
 *
 * <p>대부분의 API가 {@code Authorization: Bearer {accessToken}} 을 요구하므로,
 * 우측 상단 <b>Authorize</b> 버튼에 accessToken 을 넣으면 이후 요청에 자동으로 붙는다.
 * (로그인/회원가입 응답의 {@code data.accessToken} 값을 그대로 넣으면 된다)
 */
@Configuration
public class OpenApiConfig {

	private static final String SECURITY_SCHEME_NAME = "bearerAuth";

	@Bean
	public OpenAPI openAPI() {
		return new OpenAPI()
				.info(apiInfo())
				.servers(List.of(
						new Server().url("https://api.wish-connect.com").description("운영 서버"),
						new Server().url("http://localhost:8080").description("로컬 서버")))
				.addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
				.components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME, bearerScheme()));
	}

	private Info apiInfo() {
		return new Info()
				.title("WishConnect API")
				.description("""
						대학생 장학금 큐레이팅·AI 자기소개서 서비스 WishConnect 의 서버 API 문서입니다.

						**공통 응답 포맷**
						```json
						{ "success": true, "data": { ... }, "message": null }
						```
						실패 시 `success=false`, `data=null`, `message` 에 사용자 노출용 메시지가 담깁니다.

						**인증**
						로그인/회원가입 응답의 `data.accessToken` 을 우측 상단 **Authorize** 에 입력하면
						이후 요청 헤더에 `Authorization: Bearer {token}` 이 자동으로 붙습니다.
						""")
				.version("v1");
	}

	private SecurityScheme bearerScheme() {
		return new SecurityScheme()
				.name(SECURITY_SCHEME_NAME)
				.type(SecurityScheme.Type.HTTP)
				.scheme("bearer")
				.bearerFormat("JWT")
				.description("accessToken 값만 입력하세요. 'Bearer ' 접두사는 자동으로 붙습니다.");
	}
}
