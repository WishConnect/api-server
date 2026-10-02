package com.wishconnect.domain.auth.service;

import com.wishconnect.domain.auth.dto.request.LoginRequest;
import com.wishconnect.domain.auth.dto.response.AdminLoginAttemptResponse;
import com.wishconnect.domain.auth.dto.response.AdminLoginResponse;
import com.wishconnect.domain.auth.dto.response.AdminSessionResponse;
import com.wishconnect.domain.auth.util.LoginIdNormalizer;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.entity.UserRole;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomDetailException;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import com.wishconnect.global.jwt.AdminSessionTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 관리자 콘솔 전용 로그인. 일반 LOCAL 로그인과 달리 ADMIN 역할을 확인한 뒤에만 토큰을 발급한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final AdminLoginAttemptService loginAttemptService;
	private final AdminSessionTokens sessionTokens;
	private volatile String dummyHash;

	/**
	 * 관리자 로그인.
	 *
	 * <p>실패 처리는 계정이 없을 때와 비밀번호가 틀릴 때, ADMIN 이 아닐 때 모두 같다(같은 카운터, 같은 응답).
	 * 계정이 없을 때도 비밀번호 해시 비교를 한 번 해서 응답 시간 차이로 존재 여부가 드러나지 않게 한다.
	 *
	 * @param clientIp 프록시를 거친 실제 클라이언트 주소(IP 기준 보조 제한용)
	 */
	@Transactional(readOnly = true)
	public AdminLoginResponse login(LoginRequest request, String clientIp) {
		String loginId = LoginIdNormalizer.normalize(request.loginId());

		AdminLoginAttemptResponse locked = loginAttemptService.lockedStatus(loginId, clientIp);
		if (locked != null) {
			log.warn("[AdminAuth] 잠금 상태에서 로그인 시도 거부 scope={} ip={}", locked.lockScope(), clientIp);
			throw new CustomDetailException(ErrorCode.ADMIN_LOGIN_LOCKED, locked);
		}

		User user = userRepository.findByLoginIdAndLoginTypeAndDeletedAtIsNull(loginId, LoginType.LOCAL)
				.orElse(null);
		boolean passwordMatches = user != null && StringUtils.hasText(user.getPassword())
				? passwordEncoder.matches(request.password(), user.getPassword())
				: passwordEncoder.matches(request.password(), dummyHash());
		if (user == null || user.getRole() != UserRole.ADMIN || !passwordMatches) {
			AdminLoginAttemptResponse attempt = loginAttemptService.recordFailure(loginId, clientIp);
			throw new CustomDetailException(
					attempt.locked() ? ErrorCode.ADMIN_LOGIN_LOCKED : ErrorCode.LOGIN_FAILED, attempt);
		}

		loginAttemptService.reset(loginId);
		AdminSessionTokens.Issued issued = sessionTokens.issueNew(user.getId());
		log.info("[AdminAuth] 관리자 로그인 완료 (userId={}, ip={})", user.getId(), clientIp);
		return new AdminLoginResponse(
				issued.token(),
				issued.expiresInSeconds(),
				user.getName(),
				issued.expiresAt(),
				issued.sessionMaxExpiresAt());
	}

	/**
	 * 세션 연장(콘솔 우측 상단 "연장" 버튼). 같은 세션 시작 시각으로 새 토큰을 만든다.
	 *
	 * @param token 현재 Authorization 헤더의 토큰(이미 필터에서 유효성·ADMIN 확인을 마친 상태)
	 */
	public AdminSessionResponse extend(String token) {
		if (token == null || sessionTokens.parse(token).isEmpty()) {
			throw new CustomException(ErrorCode.ADMIN_SESSION_REQUIRED);
		}
		AdminSessionTokens.Issued issued = sessionTokens.extend(token)
				.orElseThrow(() -> new CustomException(ErrorCode.ADMIN_SESSION_EXPIRED));
		log.info("[AdminAuth] 관리자 세션 연장 (until={})", issued.expiresAt());
		return new AdminSessionResponse(issued.token(), issued.expiresAt(), issued.expiresInSeconds(),
				issued.sessionStartedAt(), issued.sessionMaxExpiresAt(), issued.sessionMaxRemainingSeconds(),
				issued.extendable(), sessionTokens.idleTimeoutSeconds());
	}

	/** 남은 시간 조회. 연장하지 않는다. */
	public AdminSessionResponse status(String token) {
		AdminSessionTokens.Status status = token == null ? null : sessionTokens.status(token).orElse(null);
		if (status == null) {
			throw new CustomException(ErrorCode.ADMIN_SESSION_REQUIRED);
		}
		return new AdminSessionResponse(null, status.expiresAt(), status.remainingSeconds(),
				status.sessionStartedAt(), status.sessionMaxExpiresAt(), status.sessionMaxRemainingSeconds(),
				status.extendable(), status.idleTimeoutSeconds());
	}

	/** 계정이 없을 때 비교할 해시. 매번 만들면 느리므로 한 번만 만든다. */
	private String dummyHash() {
		String hash = dummyHash;
		if (hash == null) {
			hash = passwordEncoder.encode("wishconnect-admin-timing-guard");
			dummyHash = hash;
		}
		return hash;
	}
}
