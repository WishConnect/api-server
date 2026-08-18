package com.wishconnect.domain.auth.service;

import com.wishconnect.domain.auth.config.EmailVerificationProperties;
import com.wishconnect.domain.auth.dto.response.PasswordResetVerifyResponse;
import com.wishconnect.domain.auth.util.LoginIdNormalizer;
import com.wishconnect.domain.auth.util.PasswordValidator;
import com.wishconnect.domain.auth.util.RecoveryKeyHasher;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
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
	private static final String TOKEN_KEY = "password:reset:token:";

	private final UserRepository userRepository;
	private final StringRedisTemplate redisTemplate;
	private final MailService mailService;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokenService;
	private final EmailVerificationProperties properties;
	private final SecureRandom random = new SecureRandom();

	/** 재설정 코드 발송 요청. 반환값은 코드 유효시간(초). 응답은 항상 동일(계정 열거 방지). */
	public long requestReset(String loginId, String email) {
		String normalizedLoginId = LoginIdNormalizer.normalize(loginId);
		String normalizedEmail = normalizeEmail(email);
		String cooldownKey = COOLDOWN_KEY
				+ RecoveryKeyHasher.hash(normalizedLoginId + "|" + normalizedEmail);
		if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
			throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
		}
		redisTemplate.opsForValue()
				.set(cooldownKey, "1", Duration.ofSeconds(properties.cooldownSeconds()));

		findUser(normalizedLoginId, normalizedEmail).ifPresentOrElse(
				user -> {
					String code = generateCode();
					redisTemplate.opsForValue()
							.set(codeKey(user.getId()), code,
									Duration.ofSeconds(properties.codeTtlSeconds()));
					mailService.sendPasswordResetCode(user.getEmail(), code);
					log.info("[PasswordReset] 재설정 코드 발송 userId={}", user.getId());
				},
				() -> log.info("[PasswordReset] 일치하는 LOCAL 계정 없는 요청 무시"));

		return properties.codeTtlSeconds();
	}

	/** 인증 코드를 검증한 뒤 비밀번호 변경에만 쓸 수 있는 일회성 토큰을 발급한다. */
	public PasswordResetVerifyResponse verifyCode(String loginId, String email, String code) {
		User user = findUser(LoginIdNormalizer.normalize(loginId), normalizeEmail(email))
				.orElseThrow(this::verificationFailed);
		String stored = redisTemplate.opsForValue().get(codeKey(user.getId()));
		if (stored == null || !stored.equals(code)) {
			throw verificationFailed();
		}
		redisTemplate.delete(codeKey(user.getId()));
		String resetToken = generateResetToken();
		redisTemplate.opsForValue().set(tokenKey(resetToken), user.getId().toString(),
				Duration.ofSeconds(properties.codeTtlSeconds()));
		return new PasswordResetVerifyResponse(resetToken, properties.codeTtlSeconds());
	}

	/** 일회성 재설정 토큰과 비밀번호 정책을 검증한 뒤 BCrypt 로 저장한다. */
	@Transactional
	public void resetPassword(String resetToken, String newPassword) {
		String tokenKey = tokenKey(resetToken);
		String userIdValue = redisTemplate.opsForValue().get(tokenKey);
		if (userIdValue == null) {
			throw new CustomException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID);
		}
		User user = userRepository.findById(parseUserId(userIdValue))
				.filter(candidate -> !candidate.isDeleted() && candidate.getLoginType() == LoginType.LOCAL)
				.orElseThrow(() -> new CustomException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID));
		if (!PasswordValidator.isValid(newPassword, user.getEmail())) {
			throw new CustomException(ErrorCode.INVALID_PASSWORD_FORMAT);
		}

		user.changePassword(passwordEncoder.encode(newPassword));
		redisTemplate.delete(tokenKey);
		// 재설정은 계정을 되찾는 절차라 기존 세션을 남겨두면 안 된다.
		// (탈취범이 이미 로그인해 있으면 비밀번호를 바꿔도 그 세션이 계속 살아있다)
		// Access Token 은 상태가 없어 만료(30분)까지는 유효하고, 재발급만 여기서 막는다.
		refreshTokenService.delete(user.getId());
		log.info("[PasswordReset] 비밀번호 변경 완료, Refresh Token 무효화 userId={}", user.getId());
	}

	private java.util.Optional<User> findUser(String loginId, String email) {
		return userRepository.findByLoginIdAndEmailIgnoreCaseAndLoginTypeAndDeletedAtIsNull(
				loginId, email, LoginType.LOCAL);
	}

	private String codeKey(UUID userId) {
		return CODE_KEY + userId;
	}

	private String tokenKey(String token) {
		return TOKEN_KEY + RecoveryKeyHasher.hash(token);
	}

	private UUID parseUserId(String value) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw new CustomException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID);
		}
	}

	private CustomException verificationFailed() {
		return new CustomException(ErrorCode.ACCOUNT_RECOVERY_VERIFICATION_FAILED);
	}

	private String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private String generateCode() {
		return String.format("%06d", random.nextInt(1_000_000));
	}

	private String generateResetToken() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
