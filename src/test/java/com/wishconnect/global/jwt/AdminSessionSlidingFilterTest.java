package com.wishconnect.global.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.wishconnect.global.config.AdminSecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("관리자 세션 슬라이딩 필터")
class AdminSessionSlidingFilterTest {

	private final JwtProvider jwtProvider = new JwtProvider(
			new JwtProperties("test-secret-test-secret-test-secret-0123456789", 1_800_000L, 1_209_600_000L));
	/** 토큰 발급(실제 시각)보다 2분 뒤를 "지금"으로 둬서, 재발급 간격(1분)을 넘긴 상태를 만든다. */
	private final AdminSessionTokens tokens = new AdminSessionTokens(jwtProvider,
			new AdminSecurityProperties(5, Duration.ofMinutes(15), 20, Duration.ofMinutes(15),
					Duration.ofMinutes(30), Duration.ofHours(8)),
			Clock.fixed(Instant.now().plusSeconds(120), ZoneOffset.UTC));
	private final AdminSessionSlidingFilter filter = new AdminSessionSlidingFilter(tokens);
	private final UUID userId = UUID.randomUUID();

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	private MockHttpServletResponse run(String path, String token, String role) throws Exception {
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		request.setRequestURI(path);
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}

	private String sessionToken() {
		Instant now = Instant.now();
		return jwtProvider.createAdminSessionToken(userId, now.minus(Duration.ofHours(1)),
				now.plus(Duration.ofMinutes(10)));
	}

	@Test
	@DisplayName("관리자 API 요청이면 새 토큰을 헤더와 쿠키로 내린다")
	void slidesOnActivity() throws Exception {
		MockHttpServletResponse response = run("/api/v1/scholarships/admin/scholarships", sessionToken(), "ADMIN");

		String newToken = response.getHeader(AdminSessionSlidingFilter.TOKEN_HEADER);
		assertThat(newToken).isNotBlank();
		assertThat(response.getHeader(AdminSessionSlidingFilter.EXPIRES_AT_HEADER)).isNotBlank();
		assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains(AdminAuthCookie.NAME + "=" + newToken)
				.contains("HttpOnly");
	}

	@Test
	@DisplayName("남은 시간 조회 등 /api/v1/admin/auth/** 는 연장하지 않는다")
	void skipsAuthEndpoints() throws Exception {
		MockHttpServletResponse response = run("/api/v1/admin/auth/session", sessionToken(), "ADMIN");

		assertThat(response.getHeader(AdminSessionSlidingFilter.TOKEN_HEADER)).isNull();
	}

	@Test
	@DisplayName("일반 로그인 ADMIN 토큰은 연장하지 않는다 — 일반 로그인 동작 불변")
	void ignoresPlainAccessToken() throws Exception {
		MockHttpServletResponse response = run("/api/v1/scholarships/admin/scholarships",
				jwtProvider.createAccessToken(userId, "ADMIN"), "ADMIN");

		assertThat(response.getHeader(AdminSessionSlidingFilter.TOKEN_HEADER)).isNull();
	}

	@Test
	@DisplayName("X-Admin-Background: true 요청은 연장하지 않는다")
	void skipsBackgroundRequests() throws Exception {
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/notifications");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken());
		request.addHeader(AdminSessionSlidingFilter.BACKGROUND_HEADER, "true");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, new MockFilterChain());

		assertThat(response.getHeader(AdminSessionSlidingFilter.TOKEN_HEADER)).isNull();
	}

	@Test
	@DisplayName("ADMIN 이 아닌 인증이면 아무것도 하지 않는다")
	void ignoresNonAdmin() throws Exception {
		MockHttpServletResponse response = run("/api/v1/users/me", sessionToken(), "USER");

		assertThat(response.getHeader(AdminSessionSlidingFilter.TOKEN_HEADER)).isNull();
	}
}
