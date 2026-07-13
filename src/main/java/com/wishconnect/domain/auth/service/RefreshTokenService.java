package com.wishconnect.domain.auth.service;

import com.wishconnect.global.jwt.JwtProvider;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Refresh Token 을 Redis 에 저장/조회/삭제하여 서버에서 유효성을 관리한다.
 * key 형식: {@code refresh:{userId}} → value: refreshToken
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

	private static final String KEY_PREFIX = "refresh:";

	private final StringRedisTemplate redisTemplate;
	private final JwtProvider jwtProvider;

	public void save(UUID userId, String refreshToken) {
		redisTemplate.opsForValue().set(
				key(userId), refreshToken, Duration.ofMillis(jwtProvider.getRefreshTokenValidity()));
	}

	public Optional<String> find(UUID userId) {
		return Optional.ofNullable(redisTemplate.opsForValue().get(key(userId)));
	}

	public void delete(UUID userId) {
		redisTemplate.delete(key(userId));
	}

	private String key(UUID userId) {
		return KEY_PREFIX + userId;
	}
}
