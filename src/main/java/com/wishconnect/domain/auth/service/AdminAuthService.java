package com.wishconnect.domain.auth.service;

import com.wishconnect.domain.auth.dto.request.LoginRequest;
import com.wishconnect.domain.auth.dto.response.AdminLoginAttemptResponse;
import com.wishconnect.domain.auth.dto.response.AdminLoginResponse;
import com.wishconnect.domain.auth.util.LoginIdNormalizer;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.entity.UserRole;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomDetailException;
import com.wishconnect.global.exception.ErrorCode;
import com.wishconnect.global.jwt.JwtProvider;
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
	private final JwtProvider jwtProvider;
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
		String accessToken = jwtProvider.createAccessToken(user.getId(), UserRole.ADMIN.name());
		log.info("[AdminAuth] 관리자 로그인 완료 (userId={}, ip={})", user.getId(), clientIp);
		return new AdminLoginResponse(
				accessToken,
				jwtProvider.getAccessTokenValidity() / 1000,
				user.getName());
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
