package com.wishconnect.global.lock;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * LLM 호출처럼 오래 걸리는 작업이 중복 실행되지 않게 막는 짧은 분산 잠금.
 *
 * <h2>왜 값에 토큰을 넣는가</h2>
 * 잠금 값을 상수로 두고 {@code finally} 에서 무조건 지우면, <b>TTL 이 만료된 뒤 다른 요청이 새로
 * 잡은 잠금을 지워 버린다.</b>
 *
 * <pre>
 * 요청A 잠금 획득 ── LLM 호출이 TTL 을 넘김 ── 잠금 만료
 *                                              요청B 잠금 획득 ─┐
 *                    요청A 종료 → delete(키)  ← B의 잠금을 지움  │
 *                                              요청C 도 진입 ────┘  (동시 실행)
 * </pre>
 *
 * <p>그래서 요청마다 UUID 를 값으로 넣고, 해제할 때 <b>값이 내 것일 때만 지운다.</b>
 * 조회와 삭제 사이에 만료될 수 있으므로 Lua 로 한 번에 처리한다(compare-and-delete).
 *
 * <p>완전한 분산 락(펜싱 토큰·자동 갱신)은 아니다. 여기서 막으려는 것은 <b>비용이 드는 작업의
 * 중복 실행</b>이고, 잠금을 놓쳐도 결과가 깨지지 않도록 DB 유니크 제약이나 상태 검사로 이중
 * 방어를 두는 것을 전제로 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLock {

	/** 값이 내 토큰일 때만 지운다. 조회·삭제 사이에 만료돼 남의 잠금을 지우는 것을 막는다. */
	private static final RedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>("""
			if redis.call('get', KEYS[1]) == ARGV[1] then
			  return redis.call('del', KEYS[1])
			else
			  return 0
			end
			""", Long.class);

	private final StringRedisTemplate redisTemplate;

	/**
	 * 잠금을 시도한다.
	 *
	 * @return 획득했으면 해제에 쓸 토큰, 이미 잠겨 있으면 {@code empty}
	 */
	public Optional<String> tryLock(String key, Duration ttl) {
		String token = UUID.randomUUID().toString();
		boolean acquired = Boolean.TRUE.equals(
				redisTemplate.opsForValue().setIfAbsent(key, token, ttl));
		return acquired ? Optional.of(token) : Optional.empty();
	}

	/**
	 * 내가 잡은 잠금만 해제한다.
	 *
	 * <p>이미 만료돼 다른 요청이 잡았다면 아무것도 하지 않는다. 그 경우를 오류로 다루지 않는 이유는,
	 * 늦게 끝난 쪽이 할 수 있는 일이 없고 새 소유자의 작업을 방해해서도 안 되기 때문이다.
	 *
	 * @return 실제로 해제했으면 true
	 */
	public boolean unlock(String key, String token) {
		if (token == null) {
			return false;
		}
		Long deleted = redisTemplate.execute(COMPARE_AND_DELETE, List.of(key), token);
		boolean released = deleted != null && deleted > 0;
		if (!released) {
			// TTL 이 만료돼 다른 요청이 이미 잡았다는 뜻이다. 그 잠금을 건드리면 안 된다.
			log.warn("잠금이 이미 만료·교체돼 해제하지 않았습니다. key={}", key);
		}
		return released;
	}
}
