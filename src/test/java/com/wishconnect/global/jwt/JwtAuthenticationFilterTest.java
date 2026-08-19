package com.wishconnect.global.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@DisplayName("JWT 인증 필터")
class JwtAuthenticationFilterTest {

	@Mock
	private JwtProvider jwtProvider;
	@Mock
	private WithdrawnTokenStore withdrawnTokenStore;
	@Mock
	private FilterChain filterChain;

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private MockHttpServletRequest requestWithToken() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer access-token");
		return request;
	}

	@Test
	@DisplayName("유효한 토큰이면 SecurityContext 에 인증을 등록한다")
	void authenticatesValidToken() throws Exception {
		UUID userId = UUID.randomUUID();
		given(jwtProvider.validateToken("access-token")).willReturn(true);
		given(jwtProvider.getUserId("access-token")).willReturn(userId);
		given(withdrawnTokenStore.isWithdrawn(userId)).willReturn(false);

		new JwtAuthenticationFilter(jwtProvider, withdrawnTokenStore)
				.doFilter(requestWithToken(), new MockHttpServletResponse(), filterChain);

		assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
				.isEqualTo(userId.toString());
		verify(filterChain).doFilter(any(), any());
	}

	/*
	Access Token 은 상태가 없어 탈퇴해도 서명은 계속 유효하다. 블랙리스트를 보지 않으면
	탈퇴한 계정이 남은 유효시간(30분) 동안 API 를 그대로 쓸 수 있다.
	 */
	@Test
	@DisplayName("서명이 유효해도 탈퇴한 계정의 토큰이면 인증하지 않는다")
	void rejectsWithdrawnUserToken() throws Exception {
		UUID userId = UUID.randomUUID();
		given(jwtProvider.validateToken("access-token")).willReturn(true);
		given(jwtProvider.getUserId("access-token")).willReturn(userId);
		given(withdrawnTokenStore.isWithdrawn(userId)).willReturn(true);

		new JwtAuthenticationFilter(jwtProvider, withdrawnTokenStore)
				.doFilter(requestWithToken(), new MockHttpServletResponse(), filterChain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	@DisplayName("토큰이 없으면 블랙리스트를 조회하지 않고 통과시킨다")
	void skipsWithoutToken() throws Exception {
		new JwtAuthenticationFilter(jwtProvider, withdrawnTokenStore)
				.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), filterChain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		verifyNoInteractions(withdrawnTokenStore);
	}

	@Test
	@DisplayName("관리자 화면 GET은 HttpOnly 쿠키의 토큰으로 인증한다")
	void authenticatesAdminViewCookie() throws Exception {
		UUID userId = UUID.randomUUID();
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/index.html");
		request.setCookies(new Cookie(AdminAuthCookie.NAME, "admin-token"));
		given(jwtProvider.validateToken("admin-token")).willReturn(true);
		given(jwtProvider.getUserId("admin-token")).willReturn(userId);
		given(jwtProvider.getRole("admin-token")).willReturn("ADMIN");
		given(withdrawnTokenStore.isWithdrawn(userId)).willReturn(false);

		new JwtAuthenticationFilter(jwtProvider, withdrawnTokenStore)
				.doFilter(request, new MockHttpServletResponse(), filterChain);

		assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
				.extracting("authority").containsExactly("ROLE_ADMIN");
	}

	@Test
	@DisplayName("관리자 변경 API POST는 쿠키만으로 인증하지 않는다")
	void ignoresAdminCookieForMutation() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/scholarships/manual");
		request.setCookies(new Cookie(AdminAuthCookie.NAME, "admin-token"));

		new JwtAuthenticationFilter(jwtProvider, withdrawnTokenStore)
				.doFilter(request, new MockHttpServletResponse(), filterChain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		verifyNoInteractions(jwtProvider, withdrawnTokenStore);
	}
}
