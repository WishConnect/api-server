package com.wishconnect.global.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 관리자 세션 슬라이딩. 관리자 세션 토큰으로 들어온 요청을 "활동"으로 보고 만료를 다시 민다.
 *
 * <p>새 토큰은 응답 헤더 {@value #TOKEN_HEADER} 와 HttpOnly 쿠키로 함께 내려간다. 콘솔은 API 호출에
 * Authorization 헤더를 쓰므로 <b>헤더의 새 토큰으로 바꿔 끼워야</b> 연장이 이어진다(쿠키는 화면 이동용).
 *
 * <p>연장하지 않는 요청:
 * <ul>
 *   <li>일반 로그인으로 받은 ADMIN 토큰 — 세션 시작 시각이 없어 대상이 아니다(일반 로그인 동작 불변)</li>
 *   <li>{@code /api/v1/admin/auth/**} — 로그인·연장·남은 시간 조회는 각자 처리한다. 특히 남은 시간 조회가
 *       세션을 늘리면 화면을 켜 두기만 해도 만료되지 않는다</li>
 *   <li>{@value #BACKGROUND_HEADER}: true — 배지 갱신처럼 사람이 하지 않은 주기 호출</li>
 * </ul>
 *
 * <p>여기서 무슨 일이 생겨도 요청 자체는 막지 않는다. 연장은 부가 기능이다.
 */
@Slf4j
@RequiredArgsConstructor
public class AdminSessionSlidingFilter extends OncePerRequestFilter {

	public static final String TOKEN_HEADER = "X-Admin-Access-Token";
	public static final String EXPIRES_AT_HEADER = "X-Admin-Session-Expires-At";
	public static final String MAX_EXPIRES_AT_HEADER = "X-Admin-Session-Max-Expires-At";
	public static final String BACKGROUND_HEADER = "X-Admin-Background";

	private static final String BEARER_PREFIX = "Bearer ";

	private final AdminSessionTokens sessionTokens;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		try {
			slide(request, response);
		} catch (RuntimeException e) {
			log.warn("[AdminSession] 세션 연장 중 오류. 요청은 그대로 진행한다: {}", e.toString());
		}
		filterChain.doFilter(request, response);
	}

	private void slide(HttpServletRequest request, HttpServletResponse response) {
		if (request.getRequestURI().startsWith("/api/v1/admin/auth/")
				|| "true".equalsIgnoreCase(request.getHeader(BACKGROUND_HEADER))) {
			return;
		}
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || authentication.getAuthorities().stream()
				.noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
			return;
		}
		String token = resolveToken(request);
		if (token == null) {
			return;
		}
		sessionTokens.parse(token)
				.filter(sessionTokens::shouldSlide)
				.flatMap(claims -> sessionTokens.extend(token))
				.ifPresent(issued -> {
					response.setHeader(TOKEN_HEADER, issued.token());
					response.setHeader(EXPIRES_AT_HEADER, issued.expiresAt().toString());
					response.setHeader(MAX_EXPIRES_AT_HEADER, issued.sessionMaxExpiresAt().toString());
					response.addHeader(HttpHeaders.SET_COOKIE, AdminAuthCookie.create(
							issued.token(), issued.expiresInSeconds(), request.isSecure()).toString());
				});
	}

	/** {@link JwtAuthenticationFilter} 와 같은 우선순위: Authorization 헤더, 없으면 관리자 쿠키. */
	public static String resolveToken(HttpServletRequest request) {
		String bearer = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (StringUtils.hasText(bearer) && bearer.startsWith(BEARER_PREFIX)) {
			return bearer.substring(BEARER_PREFIX.length());
		}
		if (request.getCookies() != null) {
			for (Cookie cookie : request.getCookies()) {
				if (AdminAuthCookie.NAME.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
					return cookie.getValue();
				}
			}
		}
		return null;
	}
}
