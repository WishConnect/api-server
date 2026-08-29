package com.wishconnect.global.seo;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wishconnect.global.config.SecurityConfig;
import com.wishconnect.global.jwt.JwtAuthenticationEntryPoint;
import com.wishconnect.global.jwt.JwtProvider;
import com.wishconnect.global.jwt.WithdrawnTokenStore;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SitemapController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class})
@DisplayName("sitemap.xml 응답")
class SitemapControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private SitemapService sitemapService;

	@MockBean
	private JwtProvider jwtProvider;

	@MockBean
	private WithdrawnTokenStore withdrawnTokenStore;

	@Test
	@DisplayName("토큰 없는 크롤러도 XML을 받을 수 있다")
	void servesXmlWithoutAuthentication() throws Exception {
		given(sitemapService.getSitemapXml()).willReturn(
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
						+ "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"></urlset>\n");

		mockMvc.perform(get("/sitemap.xml"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("application/xml"))
				.andExpect(header().string("Cache-Control", Matchers.containsString("max-age=3600")))
				.andExpect(content().string(Matchers.containsString("sitemaps.org/schemas/sitemap/0.9")));
	}
}
