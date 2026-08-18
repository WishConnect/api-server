package com.wishconnect.domain.auth.service;

import com.wishconnect.domain.auth.config.EmailVerificationProperties;
import com.wishconnect.domain.auth.util.RecoveryKeyHasher;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** LOCAL 계정의 아이디 찾기. 요청 단계에서는 계정 존재 여부를 응답으로 노출하지 않는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginIdRecoveryService {

	private static final String CODE_KEY = "login-id:find:code:";
	private static final String COOLDOWN_KEY = "login-id:find:cooldown:";

	private final UserRepository userRepository;
	private final StringRedisTemplate redisTemplate;
	private final MailService mailService;
	private final EmailVerificationProperties properties;
	private final SecureRandom random = new SecureRandom();

	public long requestCode(String email, String name) {
		String normalizedEmail = normalizeEmail(email);
		String normalizedName = name.trim();
		String cooldownKey = COOLDOWN_KEY + RecoveryKeyHasher.hash(normalizedEmail + "|" + normalizedName);
		if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
			throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
		}
		redisTemplate.opsForValue()
				.set(cooldownKey, "1", Duration.ofSeconds(properties.cooldownSeconds()));

		findUser(normalizedEmail, normalizedName).ifPresent(user -> {
			String code = generateCode();
			redisTemplate.opsForValue().set(codeKey(user), code,
					Duration.ofSeconds(properties.codeTtlSeconds()));
			mailService.sendLoginIdFindCode(user.getEmail(), code);
			log.info("[LoginIdRecovery] 인증 코드 발송 userId={}", user.getId());
		});
		return properties.codeTtlSeconds();
	}

	public String verifyAndFind(String email, String name, String code) {
		User user = findUser(normalizeEmail(email), name.trim())
				.orElseThrow(this::verificationFailed);
		String stored = redisTemplate.opsForValue().get(codeKey(user));
		if (stored == null || !stored.equals(code)) {
			throw verificationFailed();
		}
		redisTemplate.delete(codeKey(user));
		log.info("[LoginIdRecovery] 아이디 찾기 인증 완료 userId={}", user.getId());
		return user.getLoginId();
	}

	private Optional<User> findUser(String email, String name) {
		return userRepository.findByEmailIgnoreCaseAndNameAndLoginTypeAndDeletedAtIsNull(
				email, name, LoginType.LOCAL);
	}

	private String codeKey(User user) {
		return CODE_KEY + user.getId();
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
}
