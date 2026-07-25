package com.wishconnect.domain.auth.service;

import com.wishconnect.domain.auth.config.EmailVerificationProperties;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.security.SecureRandom;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 이메일 인증 코드 발송/검증. 코드·인증상태는 Redis 에 TTL 로 관리한다.
 * <ul>
 *   <li>코드: {@code email:verify:code:{email}} (TTL 5분)</li>
 *   <li>인증완료: {@code email:verify:done:{email}} (회원가입까지 유효)</li>
 *   <li>쿨다운: {@code email:verify:cooldown:{email}} (재발송 제한)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

	private static final String CODE_KEY = "email:verify:code:";
	private static final String VERIFIED_KEY = "email:verify:done:";
	private static final String USER_VERIFIED_KEY = "email:verify:done:user:";
	private static final String COOLDOWN_KEY = "email:verify:cooldown:";

	private final StringRedisTemplate redisTemplate;
	private final MailService mailService;
	private final UserRepository userRepository;
	private final EmailVerificationProperties properties;
	private final SecureRandom random = new SecureRandom();

	/** LOCAL 기준 가입 가능(중복 아님) 여부. */
	public boolean isEmailAvailable(String email) {
		return !userRepository.existsByEmailAndLoginType(email, LoginType.LOCAL);
	}

	/** 6자리 코드 생성 → Redis 저장 → SES 발송. 반환값은 코드 유효시간(초). */
	public long sendCode(String email) {
		if (Boolean.TRUE.equals(redisTemplate.hasKey(COOLDOWN_KEY + email))) {
			throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
		}
		String code = generateCode();
		redisTemplate.opsForValue()
				.set(CODE_KEY + email, code, Duration.ofSeconds(properties.codeTtlSeconds()));
		redisTemplate.opsForValue()
				.set(COOLDOWN_KEY + email, "1", Duration.ofSeconds(properties.cooldownSeconds()));

		mailService.sendVerificationCode(email, code);
		return properties.codeTtlSeconds();
	}

	/** 코드 대조 후 '인증됨' 상태 기록. */
	public void verifyCode(String email, String code) {
		verifyCodeOnly(email, code);
		redisTemplate.opsForValue()
				.set(VERIFIED_KEY + email, "1", Duration.ofSeconds(properties.verifiedTtlSeconds()));
		log.info("[EmailVerify] 인증 완료 email={}", maskEmail(email));
	}

	/** 마이페이지 이메일 변경용: 인증 완료 상태를 사용자와 이메일 조합으로 기록한다. */
	public void verifyCodeForUser(java.util.UUID userId, String email, String code) {
		verifyCodeOnly(email, code);
		redisTemplate.opsForValue()
				.set(userVerifiedKey(userId, email), "1", Duration.ofSeconds(properties.verifiedTtlSeconds()));
		log.info("[EmailVerify] 사용자 이메일 변경 인증 완료 userId={}, email={}", userId, maskEmail(email));
	}

	private void verifyCodeOnly(String email, String code) {
		String stored = redisTemplate.opsForValue().get(CODE_KEY + email);
		if (stored == null) {
			throw new CustomException(ErrorCode.VERIFICATION_CODE_EXPIRED);
		}
		if (!stored.equals(code)) {
			throw new CustomException(ErrorCode.INVALID_VERIFICATION_CODE);
		}
		redisTemplate.delete(CODE_KEY + email);
	}

	/** 회원가입 선행 검증용: 이메일이 '인증됨' 상태인지. */
	public boolean isVerified(String email) {
		return Boolean.TRUE.equals(redisTemplate.hasKey(VERIFIED_KEY + email));
	}

	public boolean isVerifiedForUser(java.util.UUID userId, String email) {
		return Boolean.TRUE.equals(redisTemplate.hasKey(userVerifiedKey(userId, email)));
	}

	/** 회원가입 완료 후 '인증됨' 상태 소거. */
	public void clearVerified(String email) {
		redisTemplate.delete(VERIFIED_KEY + email);
	}

	public void clearVerifiedForUser(java.util.UUID userId, String email) {
		redisTemplate.delete(userVerifiedKey(userId, email));
	}

	private String userVerifiedKey(java.util.UUID userId, String email) {
		return USER_VERIFIED_KEY + userId + ":" + email;
	}

	private String generateCode() {
		return String.format("%06d", random.nextInt(1_000_000));
	}

	private String maskEmail(String email) {
		int at = email.indexOf('@');
		if (at <= 1) {
			return "***" + (at >= 0 ? email.substring(at) : "");
		}
		return email.charAt(0) + "***" + email.substring(at);
	}
}
