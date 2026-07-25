package com.wishconnect.domain.auth.service;

import com.wishconnect.domain.auth.client.GoogleApiClient;
import com.wishconnect.domain.auth.client.KakaoApiClient;
import com.wishconnect.domain.auth.client.NaverApiClient;
import com.wishconnect.domain.auth.client.dto.GoogleTokenResponse;
import com.wishconnect.domain.auth.client.dto.GoogleUserResponse;
import com.wishconnect.domain.auth.client.dto.KakaoTokenResponse;
import com.wishconnect.domain.auth.client.dto.KakaoUserResponse;
import com.wishconnect.domain.auth.client.dto.NaverTokenResponse;
import com.wishconnect.domain.auth.client.dto.NaverUserResponse;
import com.wishconnect.domain.auth.dto.request.AgreementItem;
import com.wishconnect.domain.auth.dto.request.LoginRequest;
import com.wishconnect.domain.auth.dto.request.SignupRequest;
import com.wishconnect.domain.auth.dto.response.KakaoLoginResponse;
import com.wishconnect.domain.auth.dto.response.LoginResponse;
import com.wishconnect.domain.auth.dto.response.SignupResponse;
import com.wishconnect.domain.auth.dto.response.SocialLoginResponse;
import com.wishconnect.domain.auth.dto.response.TokenResponse;
import com.wishconnect.domain.auth.util.PasswordValidator;
import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.common.repository.RegionRepository;
import com.wishconnect.domain.user.entity.AgreementType;
import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.entity.UserAgreement;
import com.wishconnect.domain.user.entity.UserProfile;
import com.wishconnect.domain.user.repository.UserAgreementRepository;
import com.wishconnect.domain.user.repository.UserProfileRepository;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import com.wishconnect.global.jwt.JwtProvider;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

	private static final String KAKAO_EMAIL_FORMAT = "kakao_%d@wishconnect.kr";
	private static final String SOCIAL_EMAIL_FORMAT = "%s_%s@wishconnect.kr"; // prefix, providerId
	private static final Set<AgreementType> REQUIRED_AGREEMENTS = EnumSet.of(
			AgreementType.TERMS, AgreementType.PRIVACY, AgreementType.THIRD_PARTY, AgreementType.AGE_14);

	private final UserRepository userRepository;
	private final UserProfileRepository userProfileRepository;
	private final UserAgreementRepository userAgreementRepository;
	private final RegionRepository regionRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtProvider jwtProvider;
	private final RefreshTokenService refreshTokenService;
	private final EmailVerificationService emailVerificationService;
	private final KakaoApiClient kakaoApiClient;
	private final GoogleApiClient googleApiClient;
	private final NaverApiClient naverApiClient;

	/** 기본 회원가입: 이메일 인증·약관·비밀번호 검증 후 사용자/프로필/약관 저장 → 자체 JWT 발급. */
	@Transactional
	public SignupResponse signup(SignupRequest request) {
		if (!emailVerificationService.isVerified(request.email())) {
			throw new CustomException(ErrorCode.EMAIL_NOT_VERIFIED);
		}
		if (!PasswordValidator.isValid(request.password(), request.email())) {
			throw new CustomException(ErrorCode.INVALID_PASSWORD_FORMAT);
		}
		if (userRepository.existsByEmailAndLoginType(request.email(), LoginType.LOCAL)) {
			throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
		}
		validateRequiredAgreements(request.agreements());

		String encodedPassword = passwordEncoder.encode(request.password());
		User user = userRepository.save(
				User.createLocal(request.email(), encodedPassword, request.name(), request.phone()));
		saveProfile(user, request);
		saveAgreements(user, request.agreements());
		emailVerificationService.clearVerified(request.email());

		log.info("[Auth] 기본 회원가입 완료 (userId={})", user.getId());
		TokenPair tokens = issueTokens(user.getId());
		return new SignupResponse(user.getId(), tokens.accessToken(), tokens.refreshToken());
	}

	/** 기본 로그인. */
	@Transactional(readOnly = true)
	public LoginResponse login(LoginRequest request) {
		User user = userRepository.findByEmailAndLoginType(request.email(), LoginType.LOCAL)
				.orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
		if (user.isDeleted()) {
			throw new CustomException(ErrorCode.LOGIN_FAILED);
		}

		if (!StringUtils.hasText(user.getPassword())
				|| !passwordEncoder.matches(request.password(), user.getPassword())) {
			throw new CustomException(ErrorCode.LOGIN_FAILED);
		}

		TokenPair tokens = issueTokens(user.getId());
		return LoginResponse.of(user, tokens.accessToken(), tokens.refreshToken());
	}

	/** 카카오 소셜로그인: code 교환 → 사용자 조회 → 기존 로그인 / 신규 자동가입. */
	@Transactional
	public KakaoLoginResponse kakaoLogin(String code) {
		if (!StringUtils.hasText(code)) {
			throw new CustomException(ErrorCode.INVALID_KAKAO_CODE);
		}

		KakaoTokenResponse token = kakaoApiClient.getToken(code);
		KakaoUserResponse kakaoUser = kakaoApiClient.getUserInfo(token.accessToken());
		Long kakaoId = kakaoUser.id();

		User existing = userRepository.findByKakaoId(kakaoId).orElse(null);
		boolean isNewUser = (existing == null);
		User user = isNewUser ? registerKakaoUser(kakaoUser) : existing;
		if (user.isDeleted()) {
			throw new CustomException(ErrorCode.LOGIN_FAILED);
		}

		TokenPair tokens = issueTokens(user.getId());
		return KakaoLoginResponse.of(user, tokens.accessToken(), tokens.refreshToken(), isNewUser);
	}

	/** 구글 소셜로그인: code 교환 → 사용자 조회 → 기존 로그인 / 신규 자동가입. */
	@Transactional
	public SocialLoginResponse googleLogin(String code) {
		if (!StringUtils.hasText(code)) {
			throw new CustomException(ErrorCode.INVALID_GOOGLE_CODE);
		}
		GoogleTokenResponse token = googleApiClient.getToken(code);
		GoogleUserResponse googleUser = googleApiClient.getUserInfo(token.accessToken());

		return socialLogin(LoginType.GOOGLE, googleUser.sub(), googleUser.email(), googleUser.name(), "google");
	}

	/** 네이버 소셜로그인: state 검증 후 code 교환 → 사용자 조회 → 기존 로그인 / 신규 자동가입. */
	@Transactional
	public SocialLoginResponse naverLogin(String code, String state) {
		if (!StringUtils.hasText(code)) {
			throw new CustomException(ErrorCode.INVALID_NAVER_CODE);
		}
		// TODO: state 는 서버가 발급/저장한 값과 대조해야 하나, 현재 명세상 state 발급 흐름이 없어
		//       존재 여부만 검증한다(프론트 주도). state 발급 엔드포인트 추가 시 Redis 대조로 강화.
		if (!StringUtils.hasText(state)) {
			throw new CustomException(ErrorCode.INVALID_NAVER_STATE);
		}
		NaverTokenResponse token = naverApiClient.getToken(code, state);
		NaverUserResponse naverUser = naverApiClient.getUserInfo(token.accessToken());

		return socialLogin(LoginType.NAVER, naverUser.id(), naverUser.email(), naverUser.displayName(), "naver");
	}

	private SocialLoginResponse socialLogin(LoginType loginType, String providerId,
			String email, String name, String emailPrefix) {
		User existing = userRepository.findByLoginTypeAndProviderId(loginType, providerId).orElse(null);
		boolean isNewUser = (existing == null);
		User user = isNewUser
				? registerSocialUser(loginType, providerId, email, name, emailPrefix)
				: existing;
		if (user.isDeleted()) {
			throw new CustomException(ErrorCode.LOGIN_FAILED);
		}

		TokenPair tokens = issueTokens(user.getId());
		return SocialLoginResponse.of(user, tokens.accessToken(), tokens.refreshToken(), isNewUser);
	}

	private User registerSocialUser(LoginType loginType, String providerId,
			String email, String name, String emailPrefix) {
		String resolvedEmail = StringUtils.hasText(email)
				? email
				: String.format(SOCIAL_EMAIL_FORMAT, emailPrefix, providerId);
		User user = userRepository.save(User.createSocial(loginType, providerId, resolvedEmail, name));
		log.info("[Auth] {} 신규 회원 자동가입 (userId={}, providerId={})", loginType, user.getId(), providerId);
		return user;
	}

	/** 토큰 갱신: Redis 저장값과 대조 후 새 토큰 쌍 발급. */
	@Transactional(readOnly = true)
	public TokenResponse refresh(String refreshToken) {
		if (!jwtProvider.validateToken(refreshToken)) {
			throw new CustomException(ErrorCode.INVALID_TOKEN);
		}
		UUID userId = jwtProvider.getUserId(refreshToken);
		String stored = refreshTokenService.find(userId)
				.orElseThrow(() -> new CustomException(ErrorCode.TOKEN_NOT_FOUND));
		if (!stored.equals(refreshToken)) {
			throw new CustomException(ErrorCode.INVALID_TOKEN);
		}
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
		if (user.isDeleted()) {
			throw new CustomException(ErrorCode.LOGIN_FAILED);
		}

		TokenPair tokens = issueTokens(userId);
		return new TokenResponse(tokens.accessToken(), tokens.refreshToken());
	}

	/** 로그아웃: 해당 사용자의 Refresh Token 무효화. */
	public void logout(UUID userId) {
		refreshTokenService.delete(userId);
	}

	private void validateRequiredAgreements(List<AgreementItem> agreements) {
		Set<AgreementType> agreedTypes = agreements.stream()
				.filter(AgreementItem::isAgreed)
				.map(AgreementItem::type)
				.collect(Collectors.toSet());
		if (!agreedTypes.containsAll(REQUIRED_AGREEMENTS)) {
			throw new CustomException(ErrorCode.AGREEMENT_REQUIRED);
		}
	}

	private void saveProfile(User user, SignupRequest request) {
		Region region = StringUtils.hasText(request.region())
				? regionRepository.findByName(normalizeRegionName(request.region())).orElse(null)
				: null;
		UserProfile profile = UserProfile.builder()
				.user(user)
				.region(region)
				.birthYear(request.birthYear() == null ? null : String.valueOf(request.birthYear()))
				.gender(request.gender())
				.nationality(request.nationality())
				.onboardingStep("STEP_1")
				.isOnboardingCompleted(false)
				.build();
		userProfileRepository.save(profile);
	}

	private String normalizeRegionName(String value) {
		String normalized = value.trim();
		return switch (normalized) {
			case "서울특별시" -> "서울";
			case "부산광역시" -> "부산";
			case "대구광역시" -> "대구";
			case "인천광역시" -> "인천";
			case "광주광역시" -> "광주";
			case "대전광역시" -> "대전";
			case "울산광역시" -> "울산";
			case "세종특별자치시" -> "세종";
			case "경기도" -> "경기";
			case "강원특별자치도", "강원도" -> "강원";
			case "충청북도" -> "충북";
			case "충청남도" -> "충남";
			case "전북특별자치도", "전라북도" -> "전북";
			case "전라남도" -> "전남";
			case "경상북도" -> "경북";
			case "경상남도" -> "경남";
			case "제주특별자치도", "제주도" -> "제주";
			default -> normalized;
		};
	}

	private void saveAgreements(User user, List<AgreementItem> agreements) {
		List<UserAgreement> entities = agreements.stream()
				.map(item -> UserAgreement.builder()
						.user(user)
						.agreementType(item.type())
						.isAgreed(item.isAgreed())
						.build())
				.toList();
		userAgreementRepository.saveAll(entities);
	}

	private User registerKakaoUser(KakaoUserResponse kakaoUser) {
		Long kakaoId = kakaoUser.id();
		String email = StringUtils.hasText(kakaoUser.email())
				? kakaoUser.email()
				: String.format(KAKAO_EMAIL_FORMAT, kakaoId);
		User user = userRepository.save(User.createKakao(kakaoId, email, kakaoUser.nickname()));
		log.info("[Auth] 카카오 신규 회원 자동가입 (userId={}, kakaoId={})", user.getId(), kakaoId);
		return user;
	}

	/** Access/Refresh 토큰을 발급하고 Refresh Token 을 Redis 에 저장한다. */
	private TokenPair issueTokens(UUID userId) {
		String accessToken = jwtProvider.createAccessToken(userId);
		String refreshToken = jwtProvider.createRefreshToken(userId);
		refreshTokenService.save(userId, refreshToken);
		return new TokenPair(accessToken, refreshToken);
	}

	private record TokenPair(String accessToken, String refreshToken) {
	}
}
