package com.wishconnect.global.jwt;

import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 탈퇴한 사용자의 Access Token 을 만료 전에 무효화하기 위한 Redis 블랙리스트.
 *
 * <p>Access Token 은 상태가 없어서 발급 후에는 서버가 회수할 수 없다. 탈퇴 시 Refresh Token 만
 * 지우면 남은 Access Token 유효시간(30분) 동안 자소서 작성·신고 같은 API 가 그대로 열려 있다.
 * 그래서 탈퇴 시점에 userId 를 Access Token 유효시간만큼만 기록해 두고, 필터에서 걸러낸다.
 * TTL 이 토큰 수명과 같으므로 키가 무한히 쌓이지 않는다.
 *
 * <p>key 형식: {@code withdrawn:{userId}}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawnTokenStore {

	private static final String KEY_PREFIX = "withdrawn:";

	private final StringRedisTemplate redisTemplate;
	private final JwtProvider jwtProvider;

	/** 탈퇴 처리. 남아 있는 Access Token 이 만료될 때까지만 기록한다. */
	public void markWithdrawn(UUID userId) {
		redisTemplate.opsForValue().set(
				key(userId), "1", Duration.ofMillis(jwtProvider.getAccessTokenValidity()));
	}

	/**
	 * 탈퇴 기록 여부. 인증 필터가 모든 요청에서 호출하므로 Redis 장애 시 fail-open 한다.
	 * 여기서 예외를 던지면 Redis 가 죽는 순간 로그인한 모든 요청이 401 이 되는데,
	 * 그 손해가 탈퇴 계정이 최대 30분 남는 것보다 크다. 뒤쪽 서비스 계층에서 한 번 더 막는다.
	 */
	public boolean isWithdrawn(UUID userId) {
		try {
			return Boolean.TRUE.equals(redisTemplate.hasKey(key(userId)));
		} catch (RuntimeException e) {
			log.warn("[Auth] 탈퇴 블랙리스트 조회 실패, 통과시킴 (userId={})", userId, e);
			return false;
		}
	}

	private String key(UUID userId) {
		return KEY_PREFIX + userId;
	}
}
