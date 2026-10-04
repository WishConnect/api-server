package com.wishconnect.global.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("관리자 쓰기 중복 요청 방지")
class AdminWriteDedupeInterceptorTest {

	@Mock private StringRedisTemplate redisTemplate;
	@Mock private ValueOperations<String, String> values;
	private AdminWriteDedupeInterceptor interceptor;

	@BeforeEach
	void setUp() {
		given(redisTemplate.opsForValue()).willReturn(values);
		interceptor = new AdminWriteDedupeInterceptor(redisTemplate);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				"11111111-1111-1111-1111-111111111111", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	private MockHttpServletRequest post(String uri) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
		request.setRequestURI(uri);
		return request;
	}

	@Test
	@DisplayName("처리 중인 같은 요청은 409 DUPLICATE_REQUEST 로 거절한다")
	void rejectsInFlightDuplicate() {
		given(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60)))).willReturn(true, false);

		assertThat(interceptor.preHandle(post("/api/v1/scholarships/manual/full"), new MockHttpServletResponse(), null))
				.isTrue();
		assertThatThrownBy(() -> interceptor.preHandle(post("/api/v1/scholarships/manual/full"),
				new MockHttpServletResponse(), null))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_REQUEST);
	}

	@Test
	@DisplayName("성공하면 3초 더 막고, 실패하면 바로 풀어 다시 보낼 수 있게 한다")
	void cooldownOnSuccessReleaseOnFailure() {
		given(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60)))).willReturn(true);
		MockHttpServletRequest ok = post("/api/v1/scholarships/manual/full");
		interceptor.preHandle(ok, new MockHttpServletResponse(), null);
		MockHttpServletResponse okResponse = new MockHttpServletResponse();
		okResponse.setStatus(201);
		interceptor.afterCompletion(ok, okResponse, null, null);
		verify(redisTemplate).expire(anyString(), eq(Duration.ofSeconds(3)));

		MockHttpServletRequest bad = post("/api/v1/scholarships/manual");
		interceptor.preHandle(bad, new MockHttpServletResponse(), null);
		MockHttpServletResponse badResponse = new MockHttpServletResponse();
		badResponse.setStatus(400);
		interceptor.afterCompletion(bad, badResponse, null, null);
		verify(redisTemplate).delete(anyString());
	}

	@Test
	@DisplayName("Idempotency-Key 가 있으면 그 키로 막고 성공 후 10분 유지한다")
	void idempotencyKey() {
		given(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60)))).willReturn(true);
		MockHttpServletRequest request = post("/api/v1/scholarships/manual/full");
		request.addHeader(AdminWriteDedupeInterceptor.IDEMPOTENCY_HEADER, "form-123");

		interceptor.preHandle(request, new MockHttpServletResponse(), null);
		interceptor.afterCompletion(request, new MockHttpServletResponse(), null, null);

		assertThat(AdminWriteDedupeInterceptor.key("a", request)).startsWith("admin:dedupe:idem:a:");
		verify(redisTemplate).expire(anyString(), eq(Duration.ofMinutes(10)));
	}

	@Test
	@DisplayName("쿼리가 다르면 다른 요청이다 — 엑셀 미리보기(dryRun=true) 뒤 바로 반영(dryRun=false)은 막지 않는다")
	void queryStringDistinguishesRequests() {
		MockHttpServletRequest dry = post("/api/v1/scholarships/admin/manual-excel");
		dry.setQueryString("dryRun=true");
		MockHttpServletRequest real = post("/api/v1/scholarships/admin/manual-excel");
		real.setQueryString("dryRun=false");

		assertThat(AdminWriteDedupeInterceptor.key("a", dry)).isNotEqualTo(AdminWriteDedupeInterceptor.key("a", real));
	}

	@Test
	@DisplayName("Redis 장애 시에는 막지 않는다(fail-open), POST 가 아니면 보지 않는다")
	void failOpenAndPostOnly() {
		given(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60))))
				.willThrow(new RedisConnectionFailureException("down"));

		assertThat(interceptor.preHandle(post("/api/v1/scholarships/manual"), new MockHttpServletResponse(), null))
				.isTrue();
		MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/scholarships/manual");
		assertThat(interceptor.preHandle(get, new MockHttpServletResponse(), null)).isTrue();
		verify(values, never()).setIfAbsent(eq("never"), anyString(), eq(Duration.ZERO));
	}
}
