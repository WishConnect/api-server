package com.wishconnect.global.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 잠금 소유권 검증.
 *
 * <p>여기서 막으려는 실패는 하나다 — <b>TTL 이 만료된 뒤 다른 요청이 새로 잡은 잠금을 지우는 것.</b>
 *
 * <pre>
 * 요청A 획득 ── 작업이 TTL 을 넘김 ── 만료
 *                                     요청B 획득 ─┐
 *              요청A 종료 → 무조건 delete         │  B의 잠금이 사라져
 *                                     요청C 진입 ─┘  둘이 동시에 돈다
 * </pre>
 *
 * <p>값에 요청별 토큰을 넣고 <b>내 토큰일 때만</b> 지우면 이 경로가 막힌다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisLockTest {

	private static final String KEY = "test:lock:1";

	@Mock private StringRedisTemplate redisTemplate;
	@Mock private ValueOperations<String, String> valueOperations;

	private RedisLock lock;

	@BeforeEach
	void setUp() {
		lock = new RedisLock(redisTemplate);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
	}

	@Test
	@DisplayName("획득하면 토큰을 준다 — 해제할 때 소유권을 확인하는 데 쓴다")
	void returnsTokenOnAcquire() {
		given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
				.willReturn(true);

		Optional<String> token = lock.tryLock(KEY, Duration.ofSeconds(30));

		assertThat(token).isPresent();
		assertThat(token.get()).isNotBlank();
	}

	@Test
	@DisplayName("요청마다 다른 토큰을 쓴다 — 같으면 소유권 확인이 무의미하다")
	void issuesDistinctTokens() {
		given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
				.willReturn(true);

		assertThat(lock.tryLock(KEY, Duration.ofSeconds(30)))
				.isNotEqualTo(lock.tryLock(KEY, Duration.ofSeconds(30)));
	}

	@Test
	@DisplayName("이미 잠겨 있으면 비어 있는 값을 준다")
	void returnsEmptyWhenAlreadyLocked() {
		given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
				.willReturn(false);

		assertThat(lock.tryLock(KEY, Duration.ofSeconds(30))).isEmpty();
	}

	@Test
	@DisplayName("저장한 값이 토큰이다 — 상수를 넣으면 남의 잠금과 구별할 수 없다")
	void storesTokenAsValue() {
		given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
				.willReturn(true);

		Optional<String> token = lock.tryLock(KEY, Duration.ofSeconds(30));

		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		verify(valueOperations).setIfAbsent(eq(KEY), captor.capture(), any(Duration.class));
		assertThat(captor.getValue()).isEqualTo(token.orElseThrow());
	}

	@Test
	@DisplayName("내 잠금이면 해제한다")
	void releasesOwnLock() {
		given(redisTemplate.execute(any(RedisScript.class), any(List.class), any()))
				.willReturn(1L);

		assertThat(lock.unlock(KEY, "my-token")).isTrue();
	}

	@Test
	@DisplayName("TTL 만료 뒤 다른 요청이 잡은 잠금은 지우지 않는다 — 이 PR 리뷰에서 지적된 경로")
	void doesNotReleaseOtherOwnersLock() {
		// Lua 가 값을 비교해 0 을 돌려준다 = 내 토큰이 아니라 지우지 않았다는 뜻.
		given(redisTemplate.execute(any(RedisScript.class), any(List.class), any()))
				.willReturn(0L);

		assertThat(lock.unlock(KEY, "expired-token")).isFalse();
	}

	@Test
	@DisplayName("해제는 조회·삭제를 나누지 않고 Lua 한 번으로 한다 — 그 사이 만료되면 남의 것을 지운다")
	void comparesAndDeletesAtomically() {
		given(redisTemplate.execute(any(RedisScript.class), any(List.class), any()))
				.willReturn(1L);

		lock.unlock(KEY, "my-token");

		verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(KEY)), eq("my-token"));
		// GET 후 DELETE 로 나누면 두 명령 사이에 만료돼 남의 잠금을 지울 수 있다.
		verify(valueOperations, never()).get(anyString());
		verify(redisTemplate, never()).delete(anyString());
	}

	@Test
	@DisplayName("토큰이 없으면 아무것도 지우지 않는다")
	void ignoresNullToken() {
		assertThat(lock.unlock(KEY, null)).isFalse();

		verify(redisTemplate, never()).execute(any(RedisScript.class), any(List.class), any());
	}
}
