package com.wishconnect.domain.auth.service;

import com.wishconnect.domain.auth.dto.request.LoginRequest;
import com.wishconnect.domain.auth.dto.response.AdminLoginResponse;
import com.wishconnect.domain.auth.util.LoginIdNormalizer;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.entity.UserRole;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
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
	private final JwtProvider jwtProvider;

	@Transactional(readOnly = true)
	public AdminLoginResponse login(LoginRequest request) {
		String loginId = LoginIdNormalizer.normalize(request.loginId());
		User user = userRepository.findByLoginIdAndLoginTypeAndDeletedAtIsNull(loginId, LoginType.LOCAL)
				.orElseThrow(() -> new CustomException(ErrorCode.LOGIN_FAILED));

		if (user.getRole() != UserRole.ADMIN
				|| !StringUtils.hasText(user.getPassword())
				|| !passwordEncoder.matches(request.password(), user.getPassword())) {
			throw new CustomException(ErrorCode.LOGIN_FAILED);
		}

		String accessToken = jwtProvider.createAccessToken(user.getId(), UserRole.ADMIN.name());
		log.info("[AdminAuth] 관리자 로그인 완료 (userId={})", user.getId());
		return new AdminLoginResponse(
				accessToken,
				jwtProvider.getAccessTokenValidity() / 1000,
				user.getName());
	}
}
