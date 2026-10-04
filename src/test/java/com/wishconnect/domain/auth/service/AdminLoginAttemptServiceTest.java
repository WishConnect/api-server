package com.wishconnect.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.auth.dto.response.AdminLoginAttemptResponse;
import com.wishconnect.global.config.AdminSecurityProperties;
import java.time.Duration;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("관리자 로그인 실패 제한")
class AdminLoginAttemptServiceTest {

	private static final String IP = "203.0.113.7";

	@Mock private StringRedisTemplate redisTemplate;
	@Mock private ValueOperations<String, String> values;

	private AdminLoginAttemptService service;

	@BeforeEach
	void setUp() {
		given(redisTemplate.opsForValue()).willReturn(values);
		service = new AdminLoginAttemptService(redisTemplate, new AdminSecurityProperties(
				5, Duration.ofMinutes(15), 20, Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(8)));
	}

	@Test
	@DisplayName("4회째 실패까지는 남은 횟수를 알려 주고 잠그지 않는다")
	void countsBeforeLock() {
		given(values.increment("admin:login:fail:id:admin01")).willReturn(4L);
		given(values.increment("admin:login:fail:ip:" + IP)).willReturn(4L);

		AdminLoginAttemptResponse result = service.recordFailure("admin01", IP);

		assertThat(result.locked()).isFalse();
		assertThat(result.failedCount()).isEqualTo(4);
		assertThat(result.remainingAttempts()).isEqualTo(1);
		assertThat(result.maxFailures()).isEqualTo(5);
		assertThat(result.lockMinutes()).isEqualTo(15);
		verify(values, never()).set(eq("admin:login:lock:id:admin01"), anyString(), eq(Duration.ofMinutes(15)));
	}

	@Test
	@DisplayName("5회째 실패에서 15분 잠근다")
	void locksOnFifthFailure() {
		given(values.increment("admin:login:fail:id:admin01")).willReturn(5L);

		AdminLoginAttemptResponse result = service.recordFailure("admin01", IP);

		assertThat(result.locked()).isTrue();
		assertThat(result.lockScope()).isEqualTo("ACCOUNT");
		assertThat(result.lockRemainingSeconds()).isEqualTo(900);
		verify(values).set("admin:login:lock:id:admin01", "1", Duration.ofMinutes(15));
		// 아이디가 잠기는 실패도 IP 카운터에 들어간다.
		verify(values).increment("admin:login:fail:ip:" + IP);
	}

	@Test
	@DisplayName("첫 실패에서 카운터 만료(창)를 연다")
	void opensWindowOnFirstFailure() {
		given(values.increment("admin:login:fail:id:admin01")).willReturn(1L);
		given(values.increment("admin:login:fail:ip:" + IP)).willReturn(1L);

		service.recordFailure("admin01", IP);

		verify(redisTemplate).expire("admin:login:fail:id:admin01", Duration.ofMinutes(15));
		verify(redisTemplate).expire("admin:login:fail:ip:" + IP, Duration.ofMinutes(15));
	}

	@Test
	@DisplayName("같은 IP 에서 20회 실패하면 IP 를 잠근다 — 아이디를 돌려가며 시도하는 것을 막는다")
	void locksIpAfterTwentyFailures() {
		given(values.increment("admin:login:fail:id:other01")).willReturn(1L);
		given(values.increment("admin:login:fail:ip:" + IP)).willReturn(20L);

		AdminLoginAttemptResponse result = service.recordFailure("other01", IP);

		assertThat(result.locked()).isTrue();
		assertThat(result.lockScope()).isEqualTo("IP");
		verify(values).set("admin:login:lock:ip:" + IP, "1", Duration.ofMinutes(15));
	}

	@Test
	@DisplayName("잠금 키가 살아 있으면 남은 시간을 돌려준다")
	void reportsLockRemaining() {
		given(redisTemplate.getExpire("admin:login:lock:id:admin01")).willReturn(321L);
		given(values.get("admin:login:fail:id:admin01")).willReturn("5");

		AdminLoginAttemptResponse result = service.lockedStatus("admin01", IP);

		assertThat(result).isNotNull();
		assertThat(result.locked()).isTrue();
		assertThat(result.lockRemainingSeconds()).isEqualTo(321);
		assertThat(result.failedCount()).isEqualTo(5);
	}

	@Test
	@DisplayName("루프백 주소는 IP 제한을 걸지 않는다 — 프록시 헤더가 없으면 관리자 전원이 함께 잠긴다")
	void skipsIpLimitForLoopback() {
		given(values.increment("admin:login:fail:id:admin01")).willReturn(1L);

		service.recordFailure("admin01", "127.0.0.1");

		verify(values, never()).increment("admin:login:fail:ip:127.0.0.1");
		assertThat(AdminLoginAttemptService.ipLimited("::1")).isFalse();
		assertThat(AdminLoginAttemptService.ipLimited(IP)).isTrue();
	}

	@Test
	@DisplayName("성공하면 아이디 카운터와 잠금을 지운다")
	void resetClearsCounters() {
		service.reset("admin01");

		verify(redisTemplate).delete("admin:login:fail:id:admin01");
		verify(redisTemplate).delete("admin:login:lock:id:admin01");
	}

	@Test
	@DisplayName("Redis 장애 시에는 제한 없이 통과시킨다(fail-open)")
	void failsOpenOnRedisError() {
		given(redisTemplate.getExpire(anyString())).willThrow(new RedisConnectionFailureException("down"));
		given(values.increment(anyString())).willThrow(new RedisConnectionFailureException("down"));

		assertThat(service.lockedStatus("admin01", IP)).isNull();
		assertThat(service.recordFailure("admin01", IP).locked()).isFalse();
	}

	@Test
	@DisplayName("로그에는 아이디 앞 3글자만 남긴다")
	void masksLoginId() {
		assertThat(AdminLoginAttemptService.mask("admin01")).isEqualTo("adm***");
		assertThat(AdminLoginAttemptService.mask("ab")).isEqualTo("a**");
	}
}
