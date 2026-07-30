package com.wishconnect.global.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String token = resolveToken(request);
		if (token != null && jwtProvider.validateToken(token)) {
			UUID userId = jwtProvider.getUserId(token);
			UsernamePasswordAuthenticationToken authentication =
					new UsernamePasswordAuthenticationToken(userId.toString(), null, resolveAuthorities(token));
			SecurityContextHolder.getContext().setAuthentication(authentication);
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
		return null;
	}
}
