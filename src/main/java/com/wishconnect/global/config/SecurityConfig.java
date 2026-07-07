package com.wishconnect.global.config;

import com.wishconnect.global.jwt.JwtAuthenticationEntryPoint;
import com.wishconnect.global.jwt.JwtAuthenticationFilter;
import com.wishconnect.global.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

	private static final String[] PUBLIC_ENDPOINTS = {
			"/api/v1/auth/signup",
			"/api/v1/auth/login",
			"/api/v1/auth/kakao/**",
			"/api/v1/auth/refresh",
			"/swagger-ui/**",
			"/swagger-ui.html",
			"/v3/api-docs/**"
	};

	private final JwtProvider jwtProvider;
	private final JwtAuthenticationEntryPoint authenticationEntryPoint;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.sessionManagement(session ->
						session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
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
