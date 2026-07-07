package com.wishconnect.global.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.global.common.ApiResponse;
import com.wishconnect.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 인증이 필요한 요청에 인증 정보가 없거나 유효하지 않을 때
 * {@link ApiResponse} 형태(401 UNAUTHORIZED)로 응답한다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		ErrorCode errorCode = ErrorCode.UNAUTHORIZED;
		response.setStatus(errorCode.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(), ApiResponse.fail(errorCode.getMessage()));
	}
}
