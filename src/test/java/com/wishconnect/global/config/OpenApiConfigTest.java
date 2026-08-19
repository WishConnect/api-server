package com.wishconnect.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ObjectSchema;
import org.junit.jupiter.api.Test;

class OpenApiConfigTest {

	private final OpenApiConfig config = new OpenApiConfig();

	@Test
	void fillsOnlyMissingSchemaDescriptions() {
		ObjectSchema request = new ObjectSchema();
		ObjectSchema documented = new ObjectSchema();
		documented.setDescription("구체적인 설명");
		OpenAPI openApi = new OpenAPI().components(new Components()
				.addSchemas("LoginRequest", request)
				.addSchemas("CuratedScholarshipResponse", documented));

		config.schemaDescriptionFallback().customise(openApi);

		assertThat(request.getDescription()).isEqualTo("LoginRequest API 요청 본문");
		assertThat(documented.getDescription()).isEqualTo("구체적인 설명");
	}
}
