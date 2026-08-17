package com.wishconnect.domain.auth.service;

import com.wishconnect.domain.auth.config.EmailVerificationProperties;
import com.wishconnect.domain.auth.util.PasswordValidator;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.security.SecureRandom;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 찾기 (LOCAL 전용). 재설정 코드는 Redis 에 TTL 로 관리한다.
 * 계정 열거 방지를 위해 요청 응답은 가입 여부와 무관하게 동일하며,
 * 실제 코드 발송은 LOCAL 가입자에게만 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

	private static final String CODE_KEY = "password:reset:code:";
	private static final String COOLDOWN_KEY = "password:reset:cooldown:";

	private final UserRepository userRepository;
	private final StringRedisTemplate redisTemplate;
	private final MailService mailService;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokenService;
	private final EmailVerificationProperties properties;
	private final SecureRandom random = new SecureRandom();

	/** 재설정 코드 발송 요청. 반환값은 코드 유효시간(초). 응답은 항상 동일(계정 열거 방지). */
	public long requestReset(String email) {
		if (Boolean.TRUE.equals(redisTemplate.hasKey(COOLDOWN_KEY + email))) {
			throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
		}
		redisTemplate.opsForValue()
				.set(COOLDOWN_KEY + email, "1", Duration.ofSeconds(properties.cooldownSeconds()));

		userRepository.findByEmailAndLoginTypeAndDeletedAtIsNull(email, LoginType.LOCAL).ifPresentOrElse(
				user -> {
					String code = generateCode();
					redisTemplate.opsForValue()
							.set(CODE_KEY + email, code, Duration.ofSeconds(properties.codeTtlSeconds()));
					mailService.sendPasswordResetCode(email, code);
					log.info("[PasswordReset] 재설정 코드 발송 email={}", maskEmail(email));
				},
				() -> log.info("[PasswordReset] 미가입/소셜 계정 요청 무시 email={}", maskEmail(email)));

		return properties.codeTtlSeconds();
	}

	/** 코드 검증 + 비밀번호 정책 검증 후 BCrypt 로 저장. */
	@Transactional
	public void resetPassword(String email, String code, String newPassword) {
		String stored = redisTemplate.opsForValue().get(CODE_KEY + email);
		if (stored == null) {
			throw new CustomException(ErrorCode.VERIFICATION_CODE_EXPIRED);
		}
		if (!stored.equals(code)) {
			throw new CustomException(ErrorCode.INVALID_VERIFICATION_CODE);
		}
		User user = userRepository.findByEmailAndLoginTypeAndDeletedAtIsNull(email, LoginType.LOCAL)
				.orElseThrow(() -> new CustomException(ErrorCode.INVALID_VERIFICATION_CODE));
		if (!PasswordValidator.isValid(newPassword, email)) {
			throw new CustomException(ErrorCode.INVALID_PASSWORD_FORMAT);
		}

		user.changePassword(passwordEncoder.encode(newPassword));
		redisTemplate.delete(CODE_KEY + email);
		// 재설정은 계정을 되찾는 절차라 기존 세션을 남겨두면 안 된다.
		// (탈취범이 이미 로그인해 있으면 비밀번호를 바꿔도 그 세션이 계속 살아있다)
		// Access Token 은 상태가 없어 만료(30분)까지는 유효하고, 재발급만 여기서 막는다.
		refreshTokenService.delete(user.getId());
		log.info("[PasswordReset] 비밀번호 변경 완료, Refresh Token 무효화 userId={}", user.getId());
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
