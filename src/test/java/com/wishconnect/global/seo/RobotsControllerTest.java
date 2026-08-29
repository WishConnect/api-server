package com.wishconnect.global.seo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wishconnect.global.config.SecurityConfig;
import com.wishconnect.global.jwt.JwtAuthenticationEntryPoint;
import com.wishconnect.global.jwt.JwtProvider;
import com.wishconnect.global.jwt.WithdrawnTokenStore;
import java.time.Duration;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RobotsController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class})
@EnableConfigurationProperties({RobotsProperties.class, SitemapProperties.class})
@TestPropertySource(properties = {
		"app.sitemap.base-url=https://wish-connect.com",
		"app.robots.disallow-paths=/api/,/admin"
})
@DisplayName("robots.txt 응답")
class RobotsControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private JwtProvider jwtProvider;

	@MockBean
	private WithdrawnTokenStore withdrawnTokenStore;

	@Test
	@DisplayName("인증 없이 text/plain 으로 내려간다")
	void servesPlainTextWithoutAuthentication() throws Exception {
		// HTML 이나 4xx/5xx 로 나가면 네이버가 규칙을 무시하거나 전체 차단으로 해석한다.
		mockMvc.perform(get("/robots.txt"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/plain"));
	}

	@Test
	@DisplayName("네이버 검색로봇(Yeti)을 명시 허용하고 사이트맵 위치와 차단 경로를 적는다")
	void allowsNaverBotAndDeclaresSitemap() throws Exception {
		mockMvc.perform(get("/robots.txt"))
				.andExpect(content().string(Matchers.stringContainsInOrder(
						"User-agent: *", "Allow: /", "User-agent: Yeti", "Allow: /",
						"Sitemap: https://wish-connect.com/sitemap.xml")))
				.andExpect(content().string(Matchers.containsString("Disallow: /api/")))
				.andExpect(content().string(Matchers.containsString("Disallow: /admin")));
	}

	@Test
	@DisplayName("차단 경로는 로봇 그룹마다 반복해 적는다")
	void repeatsRulesForEachGroup() {
		// 로봇 배제 표준은 가장 구체적인 그룹 하나만 적용한다. Yeti 그룹에 Disallow 를 빠뜨리면
		// 네이버만 관리자 페이지를 긁어간다.
		RobotsController controller = new RobotsController(
				new RobotsProperties(List.of("/admin")),
				new SitemapProperties("https://wish-connect.com", null, null, true, 0, Duration.ofHours(1)));

		String body = controller.render();

		org.assertj.core.api.Assertions.assertThat(body.split("Disallow: /admin", -1)).hasSize(3);
	}
}
