package com.wishconnect.domain.user.service;

import com.wishconnect.domain.application.entity.EssayStatus;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.auth.service.EmailVerificationService;
import com.wishconnect.domain.auth.service.RefreshTokenService;
import com.wishconnect.domain.auth.util.PasswordValidator;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.domain.user.dto.request.EmailCheckRequest;
import com.wishconnect.domain.user.dto.request.EmailUpdateRequest;
import com.wishconnect.domain.user.dto.request.EmailVerificationConfirmRequest;
import com.wishconnect.domain.user.dto.request.EmailVerificationSendRequest;
import com.wishconnect.domain.user.dto.request.PasswordUpdateRequest;
import com.wishconnect.domain.user.dto.response.EmailCheckResponse;
import com.wishconnect.domain.user.dto.response.MyPageResponse;
import com.wishconnect.domain.user.dto.response.ProfileResponse;
import com.wishconnect.domain.user.dto.response.UpdateResponse;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/*
마이페이지의 내 정보 요약, 계정 보안 변경, 회원 탈퇴를 담당합니다.
프로필 상세/추천 기준 수정은 기존 UserProfileService의 온보딩 API를 재사용합니다.
 */
@Service
@RequiredArgsConstructor
public class UserAccountService {

	private final UserRepository userRepository;
	private final ScrapRepository scrapRepository;
	private final EssayRepository essayRepository;
	private final UserProfileService userProfileService;
	private final EmailVerificationService emailVerificationService;
	private final RefreshTokenService refreshTokenService;
	private final PasswordEncoder passwordEncoder;

	@Transactional(readOnly = true)
	public MyPageResponse getMyPage(UUID userId) {
		User user = getActiveUser(userId);
		ProfileResponse profile = userProfileService.getProfile(userId);
		ProfileResponse.Academic academic = profile.academic();
		ProfileResponse.Household household = profile.household();

		return new MyPageResponse(
				user.getId(),
				user.getName(),
				user.getEmail(),
				profile.birthYear(),
				profile.region(),
				profile.profileCompletionRate(),
				scrapRepository.countByUser_Id(userId),
				essayRepository.countByUser_Id(userId),
				essayRepository.countByUser_IdAndStatus(userId, EssayStatus.COMPLETED),
				new MyPageResponse.RecommendationCriteria(
						academic == null ? null : academic.grade(),
						academic == null ? null : academic.cumulativeGpa(),
						household == null ? null : household.incomeLevel(),
						profile.interests()
				)
		);
	}

	@Transactional
	public UpdateResponse updatePassword(UUID userId, PasswordUpdateRequest request) {
		User user = getActiveUser(userId);
		if (user.getLoginType() != LoginType.LOCAL || !StringUtils.hasText(user.getPassword())) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		if (!request.newPassword().equals(request.newPasswordConfirm())) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		if (!PasswordValidator.isValid(request.newPassword(), user.getEmail())) {
			throw new CustomException(ErrorCode.INVALID_PASSWORD_FORMAT);
		}
		user.changePassword(passwordEncoder.encode(request.newPassword()));
		refreshTokenService.delete(userId);
		return new UpdateResponse(true);
	}

	@Transactional(readOnly = true)
	public EmailCheckResponse checkEmail(EmailCheckRequest request) {
		return new EmailCheckResponse(emailVerificationService.isEmailAvailable(request.email()));
	}

	@Transactional
	public UpdateResponse sendEmailVerification(UUID userId, EmailVerificationSendRequest request) {
		getActiveUser(userId);
		if (!emailVerificationService.isEmailAvailable(request.email())) {
			throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
		}
		emailVerificationService.sendCode(request.email());
		return new UpdateResponse(true);
	}

	@Transactional
	public UpdateResponse verifyEmail(UUID userId, EmailVerificationConfirmRequest request) {
		getActiveUser(userId);
		emailVerificationService.verifyCodeForUser(userId, request.email(), request.code());
		return new UpdateResponse(true);
	}

	@Transactional
	public UpdateResponse updateEmail(UUID userId, EmailUpdateRequest request) {
		User user = getActiveUser(userId);
		if (!emailVerificationService.isVerifiedForUser(userId, request.email())) {
			throw new CustomException(ErrorCode.EMAIL_NOT_VERIFIED);
		}
		if (!request.email().equals(user.getEmail()) && !emailVerificationService.isEmailAvailable(request.email())) {
			throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
		}
		user.changeEmail(request.email());
		emailVerificationService.clearVerifiedForUser(userId, request.email());
		return new UpdateResponse(true);
	}

	@Transactional
	public void deleteMe(UUID userId) {
		User user = getActiveUser(userId);
		user.softDelete();
		refreshTokenService.delete(userId);
	}

	private User getActiveUser(UUID userId) {
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
		if (user.isDeleted()) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		return user;
	}
}
