package com.wishconnect.global.web;

import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 관리자 쓰기 요청의 중복 실행 방지(더블 클릭, 응답이 늦어 다시 누름).
 *
 * <p>수기 등록이 두 번 눌려 같은 장학금이 두 건 생기고, LLM·수집 트리거가 두 번 눌려 비용이 두 번 나가는 것을 막는다.
 *
 * <ul>
 *   <li>요청 헤더 {@value #IDEMPOTENCY_HEADER} 가 있으면 관리자 + 그 키로 막는다. 성공 후 10분간 같은 키는 거절한다.
 *       콘솔이 폼을 열 때 키를 하나 만들어 저장 버튼에 쓰면, 재전송이 몇 번이든 한 번만 처리된다.</li>
 *   <li>헤더가 없으면 관리자 + 메서드 + 경로(쿼리 포함)로 막는다. 처리 중에는 같은 요청을 거절하고, 끝난 뒤 3초간 더 거절한다.</li>
 *   <li>요청이 실패(4xx·5xx)하면 키를 바로 지운다 — 고쳐서 다시 보내는 것을 막지 않기 위해서다.</li>
 * </ul>
 *
 * <p>Redis 장애 시에는 막지 않고 통과시킨다(fail-open). 관리자 작업이 멈추는 쪽이 더 큰 사고다.
 */
@Slf4j
@RequiredArgsConstructor
public class AdminWriteDedupeInterceptor implements HandlerInterceptor {

	public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
	static final Duration IN_FLIGHT_TTL = Duration.ofSeconds(60);
	static final Duration COOLDOWN = Duration.ofSeconds(3);
	static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(10);
	private static final String KEY_ATTRIBUTE = AdminWriteDedupeInterceptor.class.getName() + ".key";

	private final StringRedisTemplate redisTemplate;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!"POST".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		String actor = actor();
		if (actor == null) {
			return true;
		}
		String key = key(actor, request);
		try {
			Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", IN_FLIGHT_TTL);
			if (Boolean.FALSE.equals(acquired)) {
				log.warn("[AdminDedupe] 중복 요청 거절 {} {}", request.getMethod(), request.getRequestURI());
				throw new CustomException(ErrorCode.DUPLICATE_REQUEST);
			}
			request.setAttribute(KEY_ATTRIBUTE, key);
		} catch (CustomException e) {
			throw e;
		} catch (RuntimeException e) {
			log.error("[AdminDedupe] Redis 오류로 중복 방지를 건너뜀: {}", e.toString());
		}
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		Object key = request.getAttribute(KEY_ATTRIBUTE);
		if (!(key instanceof String value)) {
			return;
		}
		try {
			if (ex != null || response.getStatus() >= 400) {
				redisTemplate.delete(value);
			} else {
				redisTemplate.expire(value, value.contains(":idem:") ? IDEMPOTENCY_TTL : COOLDOWN);
			}
		} catch (RuntimeException e) {
			log.warn("[AdminDedupe] 키 정리 실패(만료로 풀린다): {}", e.toString());
		}
	}

	static String key(String actor, HttpServletRequest request) {
		String idempotencyKey = request.getHeader(IDEMPOTENCY_HEADER);
		if (StringUtils.hasText(idempotencyKey)) {
			return "admin:dedupe:idem:" + actor + ":" + sha256(idempotencyKey.trim());
		}
		String target = request.getMethod() + " " + request.getRequestURI()
				+ (request.getQueryString() == null ? "" : "?" + request.getQueryString());
		return "admin:dedupe:req:" + actor + ":" + sha256(target);
	}

	private static String actor() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication == null || !authentication.isAuthenticated() ? null : authentication.getName();
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
