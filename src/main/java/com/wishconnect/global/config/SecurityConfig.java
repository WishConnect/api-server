package com.wishconnect.global.config;

import com.wishconnect.global.jwt.JwtAuthenticationEntryPoint;
import com.wishconnect.global.jwt.JwtAuthenticationFilter;
import com.wishconnect.global.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private static final String[] PUBLIC_ENDPOINTS = {
			"/api/v1/auth/signup",
			"/api/v1/auth/login",
			"/api/v1/auth/kakao/**",
			"/api/v1/auth/google/**",
			"/api/v1/auth/naver/**",
			"/api/v1/auth/email/**",
			"/api/v1/auth/password/**",
			"/api/v1/auth/refresh",
			"/api/v1/universities/search",
			"/api/v1/majors/search",
			"/actuator/health",
			"/swagger-ui/**",
			"/swagger-ui.html",
			"/v3/api-docs/**",
			"/api/v1/scholarships/search",
			/*
			관리자 화면의 정적 파일(HTML/JS)만 공개한다. 데이터는 전부 ADMIN 전용 API 로만 오므로
			이 파일 자체에는 비밀이 없다. 브라우저가 토큰 없이 첫 요청을 보내기 때문에 열어둔다.

			⚠️ 실질적인 접근 통제는 Nginx IP allowlist 로 한다. deploy/README.md 참고.
			 */
			"/admin",
			"/admin/**"
	};

	/**
	 * 운영/관리용 수동 트리거. 외부 API 호출·크롤링·LLM 과금을 유발하므로 ADMIN 만 허용한다.
	 * 컨트롤러의 {@code @PreAuthorize} 와 이중으로 막아, 새 관리 엔드포인트가 추가될 때
	 * 어노테이션을 빠뜨려도 경로 규칙으로 걸리도록 한다.
	 */
	private static final String[] ADMIN_ENDPOINTS = {
			"/api/v1/scholarships/admin/**",
			"/api/v1/scholarships/sync",
			"/api/v1/scholarships/collect/**",
			"/api/v1/scholarships/conditions/**",
			"/api/v1/scholarships/manual/**",
			"/api/v1/scholarships/reports",
			"/api/v1/scholarships/reports/*",
			"/api/v1/universities/sync",
			"/api/v1/universities/sync/**"
	};

	private final JwtProvider jwtProvider;
	private final JwtAuthenticationEntryPoint authenticationEntryPoint;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.cors(Customizer.withDefaults())
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.sessionManagement(session ->
						session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(ADMIN_ENDPOINTS).hasRole("ADMIN")
						.requestMatchers(PUBLIC_ENDPOINTS).permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(handler ->
						handler.authenticationEntryPoint(authenticationEntryPoint))
				.addFilterBefore(new JwtAuthenticationFilter(jwtProvider),
						UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
