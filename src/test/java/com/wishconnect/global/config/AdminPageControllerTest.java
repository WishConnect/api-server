package com.wishconnect.global.config;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wishconnect.global.jwt.AdminAuthCookie;
import com.wishconnect.global.jwt.JwtAuthenticationEntryPoint;
import com.wishconnect.global.jwt.JwtProvider;
import com.wishconnect.global.jwt.WithdrawnTokenStore;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminPageController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class})
@DisplayName("관리자 콘솔 화면 접근")
class AdminPageControllerTest {

	@Autowired private MockMvc mockMvc;
	@MockBean private JwtProvider jwtProvider;
	@MockBean private WithdrawnTokenStore withdrawnTokenStore;

	@Test
	@DisplayName("짧은 /admin 주소는 보호 대상 콘솔로 보낸다")
	void redirectsShortUrl() throws Exception {
		mockMvc.perform(get("/admin"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin/console"));
	}

	@Test
	@DisplayName("인증 쿠키가 없으면 로그인 화면으로 보낸다")
	void redirectsAnonymousToLogin() throws Exception {
		mockMvc.perform(get("/admin/console"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin/login.html"));
	}

	@Test
	@DisplayName("ADMIN 쿠키가 있으면 콘솔 HTML을 반환한다")
	void servesConsoleForAdmin() throws Exception {
		UUID userId = UUID.randomUUID();
		given(jwtProvider.validateToken("admin-token")).willReturn(true);
		given(jwtProvider.getUserId("admin-token")).willReturn(userId);
		given(jwtProvider.getRole("admin-token")).willReturn("ADMIN");
		given(withdrawnTokenStore.isWithdrawn(userId)).willReturn(false);

		mockMvc.perform(get("/admin/console")
						.cookie(new Cookie(AdminAuthCookie.NAME, "admin-token")))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/html"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("WishConnect Admin")));
	}
}
