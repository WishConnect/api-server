package com.wishconnect.global.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청의 {@code Authorization: Bearer} Access Token 을 검증하고
 * 유효하면 SecurityContext 에 인증 정보를 등록한다.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";
	private static final String ROLE_PREFIX = "ROLE_";
	private static final String DEFAULT_ROLE = "USER";

	private final JwtProvider jwtProvider;
	private final WithdrawnTokenStore withdrawnTokenStore;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String token = resolveToken(request);
		if (token != null && jwtProvider.validateToken(token)) {
			UUID userId = jwtProvider.getUserId(token);
			// 서명이 유효해도 탈퇴한 계정의 토큰이면 인증하지 않는다.
			// (인증 정보를 넣지 않으면 EntryPoint 가 401 로 응답한다)
			if (!withdrawnTokenStore.isWithdrawn(userId)) {
				UsernamePasswordAuthenticationToken authentication =
						new UsernamePasswordAuthenticationToken(userId.toString(), null, resolveAuthorities(token));
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
		}
		filterChain.doFilter(request, response);
	}

	/**
	 * role 클레임을 Spring Security 권한으로 변환한다.
	 * 클레임 도입 전에 발급된 토큰에는 값이 없으므로 일반 사용자로 취급한다.
	 */
	private List<GrantedAuthority> resolveAuthorities(String token) {
		String role = jwtProvider.getRole(token);
		String authority = ROLE_PREFIX + (role == null ? DEFAULT_ROLE : role);
		return List.of(new SimpleGrantedAuthority(authority));
	}

	private String resolveToken(HttpServletRequest request) {
		String bearer = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (StringUtils.hasText(bearer) && bearer.startsWith(BEARER_PREFIX)) {
			return bearer.substring(BEARER_PREFIX.length());
		}
		// 관리자 쿠키는 화면과 API 문서를 읽는 GET/HEAD 에서만 인정한다.
		// 변경 API는 계속 Authorization 헤더가 필요해, 브라우저가 쿠키를 자동 전송하는 CSRF를 막는다.
		if (isAdminViewRequest(request) && request.getCookies() != null) {
			for (Cookie cookie : request.getCookies()) {
				if (AdminAuthCookie.NAME.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
					return cookie.getValue();
				}
			}
		}
		return null;
	}

	private boolean isAdminViewRequest(HttpServletRequest request) {
		String method = request.getMethod();
		if (!("GET".equals(method) || "HEAD".equals(method))) {
			return false;
		}
		String path = request.getRequestURI();
		return path.startsWith("/admin/")
				|| path.startsWith("/swagger-ui/")
				|| "/swagger-ui.html".equals(path)
				|| path.startsWith("/v3/api-docs/")
				|| "/v3/api-docs".equals(path);
	}
}
