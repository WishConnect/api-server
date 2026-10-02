package com.wishconnect.domain.auth.service;

import com.wishconnect.domain.auth.dto.response.AdminLoginAttemptResponse;
import com.wishconnect.global.config.AdminSecurityProperties;
import java.net.InetAddress;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 관리자 로그인 실패 횟수 제한(Redis).
 *
 * <ul>
 *   <li><b>loginId 기준</b> — 연속 {@code loginMaxFailures}회 실패하면 {@code loginLockDuration} 동안 잠근다.
 *       <b>존재하지 않는 loginId 도 똑같이 센다.</b> 그래야 응답 차이로 계정 존재 여부를 알 수 없다.</li>
 *   <li><b>IP 기준(보조)</b> — {@code ipWindow} 안에서 {@code ipMaxFailures}회 실패하면 그 IP 를 같은 시간만큼 잠근다.
 *       여러 아이디를 돌려가며 시도하는 것을 막는다.</li>
 * </ul>
 *
 * <p>성공하면 그 loginId 의 카운터만 지운다. IP 카운터는 남긴다 — 유효한 계정 하나로 IP 카운터를 계속
 * 초기화하며 다른 계정을 두드리는 것을 막기 위해서다.
 *
 * <p><b>Redis 장애 시에는 제한 없이 통과시킨다(fail-open).</b> Redis 가 죽으면 관리자 9명이 전부 로그인을
 * 못 하게 되는 쪽이 더 큰 운영 사고라고 판단했다. 대신 ERROR 로그를 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLoginAttemptService {

	static final String ID_FAIL_PREFIX = "admin:login:fail:id:";
	static final String ID_LOCK_PREFIX = "admin:login:lock:id:";
	static final String IP_FAIL_PREFIX = "admin:login:fail:ip:";
	static final String IP_LOCK_PREFIX = "admin:login:lock:ip:";

	private final StringRedisTemplate redisTemplate;
	private final AdminSecurityProperties properties;

	/** 잠겨 있으면 잠금 정보를, 아니면 {@code null} 을 돌려준다. 비밀번호 확인 <b>전에</b> 부른다. */
	public AdminLoginAttemptResponse lockedStatus(String loginId, String ip) {
		try {
			long idRemaining = remainingSeconds(ID_LOCK_PREFIX + key(loginId));
			if (idRemaining > 0) {
				return locked("ACCOUNT", idRemaining, failures(ID_FAIL_PREFIX + key(loginId)));
			}
			if (ipLimited(ip)) {
				long ipRemaining = remainingSeconds(IP_LOCK_PREFIX + ip);
				if (ipRemaining > 0) {
					return locked("IP", ipRemaining, failures(ID_FAIL_PREFIX + key(loginId)));
				}
			}
			return null;
		} catch (RuntimeException e) {
			log.error("[AdminAuth] 로그인 제한 조회 실패(Redis). 제한 없이 진행한다: {}", e.toString());
			return null;
		}
	}

	/** 실패를 기록하고, 이번 실패로 잠겼는지까지 반영한 결과를 돌려준다. */
	public AdminLoginAttemptResponse recordFailure(String loginId, String ip) {
		int max = properties.loginMaxFailures();
		try {
			String idKey = ID_FAIL_PREFIX + key(loginId);
			long count = increment(idKey, properties.loginLockDuration());
			if (count >= max) {
				redisTemplate.opsForValue().set(ID_LOCK_PREFIX + key(loginId), "1", properties.loginLockDuration());
				log.warn("[AdminAuth] 로그인 잠금(아이디) loginId={} ip={} failures={}", mask(loginId), ip, count);
				return locked("ACCOUNT", properties.loginLockDuration().toSeconds(), (int) count);
			}
			if (ipLimited(ip)) {
				long ipCount = increment(IP_FAIL_PREFIX + ip, properties.ipWindow());
				if (ipCount >= properties.ipMaxFailures()) {
					redisTemplate.opsForValue().set(IP_LOCK_PREFIX + ip, "1", properties.ipWindow());
					log.warn("[AdminAuth] 로그인 잠금(IP) ip={} failures={}", ip, ipCount);
					return locked("IP", properties.ipWindow().toSeconds(), (int) count);
				}
			}
			log.warn("[AdminAuth] 로그인 실패 loginId={} ip={} failures={}/{}", mask(loginId), ip, count, max);
			return new AdminLoginAttemptResponse((int) count, max, (int) Math.max(max - count, 0),
					properties.loginLockDuration().toMinutes(), false, null, 0);
		} catch (RuntimeException e) {
			log.error("[AdminAuth] 로그인 실패 기록 실패(Redis): {}", e.toString());
			return new AdminLoginAttemptResponse(0, max, max, properties.loginLockDuration().toMinutes(),
					false, null, 0);
		}
	}

	/** 성공하면 그 loginId 의 실패 카운터를 지운다. */
	public void reset(String loginId) {
		try {
			redisTemplate.delete(ID_FAIL_PREFIX + key(loginId));
			redisTemplate.delete(ID_LOCK_PREFIX + key(loginId));
		} catch (RuntimeException e) {
			log.error("[AdminAuth] 로그인 카운터 초기화 실패(Redis): {}", e.toString());
		}
	}

	private AdminLoginAttemptResponse locked(String scope, long remainingSeconds, int failures) {
		return new AdminLoginAttemptResponse(failures, properties.loginMaxFailures(), 0,
				properties.loginLockDuration().toMinutes(), true, scope, remainingSeconds);
	}

	private long increment(String key, Duration window) {
		Long count = redisTemplate.opsForValue().increment(key);
		if (count != null && count == 1L) {
			// 첫 실패부터 창을 연다. 창이 끝나면 카운터가 저절로 사라진다.
			redisTemplate.expire(key, window);
		}
		return count == null ? 0L : count;
	}

	private int failures(String key) {
		String value = redisTemplate.opsForValue().get(key);
		return value == null ? 0 : Integer.parseInt(value);
	}

	private long remainingSeconds(String key) {
		Long ttl = redisTemplate.getExpire(key);
		return ttl == null || ttl < 0 ? 0 : ttl;
	}

	/**
	 * IP 제한을 걸 수 있는 주소인지.
	 *
	 * <p>루프백(127.0.0.1, ::1)은 걸지 않는다. 프록시가 X-Forwarded-For 를 넘기지 않으면 모든 요청이
	 * 루프백으로 보이는데, 그때 IP 제한을 걸면 <b>관리자 전원이 하나의 카운터를 공유해</b> 함께 잠긴다.
	 * SSH 터널로 접속하는 경우도 루프백이다. 이때는 loginId 제한만 동작한다.
	 */
	static boolean ipLimited(String ip) {
		if (!StringUtils.hasText(ip)) {
			return false;
		}
		try {
			return !InetAddress.getByName(ip).isLoopbackAddress();
		} catch (Exception e) {
			return false;
		}
	}

	/** loginId 는 이미 정규화(소문자·trim)된 값이 온다. 비어 있으면 같은 키를 쓴다. */
	private static String key(String loginId) {
		return StringUtils.hasText(loginId) ? loginId : "_blank";
	}

	/** 아이디 칸에 비밀번호를 잘못 넣는 경우가 있어 로그에는 앞 3글자만 남긴다. */
	static String mask(String loginId) {
		if (!StringUtils.hasText(loginId)) {
			return "(blank)";
		}
		return loginId.length() <= 3 ? loginId.charAt(0) + "**" : loginId.substring(0, 3) + "***";
	}
}
