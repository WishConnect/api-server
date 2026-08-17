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
import com.wishconnect.domain.common.service.RegionResolver;
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

	/** 영문 소문자·숫자·언더스코어 4~20자. 이메일과 헷갈리지 않도록 @ 와 점은 막는다. */
	private static final java.util.regex.Pattern LOGIN_ID_PATTERN =
			java.util.regex.Pattern.compile("^[a-z0-9_]{4,20}$");

	private static final String SOCIAL_EMAIL_FORMAT = "%s_%s@wishconnect.kr"; // prefix, providerId
	/**
	 * 소셜 로그인에서 이메일을 못 받았을 때 채워 넣던 자리표시자 주소의 도메인.
	 * 실제 수신함이 아니라 알림 메일이 발송되지 않으므로, 실제 이메일을 받으면 교체 대상이다.
	 */
	private static final String PLACEHOLDER_EMAIL_DOMAIN = "@wishconnect.kr";
	private static final Set<AgreementType> REQUIRED_AGREEMENTS = EnumSet.of(
			AgreementType.TERMS, AgreementType.PRIVACY, AgreementType.THIRD_PARTY, AgreementType.AGE_14);

	private final UserRepository userRepository;
	private final UserProfileRepository userProfileRepository;
	private final UserAgreementRepository userAgreementRepository;
	private final RegionResolver regionResolver;
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
		if (userRepository.existsByEmailAndLoginTypeAndDeletedAtIsNull(request.email(), LoginType.LOCAL)) {
			throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
		}
		String loginId = normalizeLoginId(request.loginId());
		if (userRepository.existsByLoginIdAndDeletedAtIsNull(loginId)) {
			throw new CustomException(ErrorCode.DUPLICATE_LOGIN_ID);
		}
		validateRequiredAgreements(request.agreements());

		String encodedPassword = passwordEncoder.encode(request.password());
		User user = userRepository.save(
				User.createLocal(request.email(), loginId, encodedPassword, request.name(), request.phone()));
		saveProfile(user, request);
		saveAgreements(user, request.agreements());
		emailVerificationService.clearVerified(request.email());

		log.info("[Auth] 기본 회원가입 완료 (userId={})", user.getId());
		TokenPair tokens = issueTokens(user);
		return new SignupResponse(user.getId(), tokens.accessToken(), tokens.refreshToken());
	}

	/** 기본 로그인. */
	@Transactional(readOnly = true)
	public LoginResponse login(LoginRequest request) {
		// 탈퇴 회원은 조회 단계에서 걸러진다 → 미가입과 동일한 응답(계정 존재 여부를 흘리지 않는다).
		User user = userRepository.findByEmailAndLoginTypeAndDeletedAtIsNull(request.email(), LoginType.LOCAL)
				.orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

		if (!StringUtils.hasText(user.getPassword())
				|| !passwordEncoder.matches(request.password(), user.getPassword())) {
			throw new CustomException(ErrorCode.LOGIN_FAILED);
		}

		TokenPair tokens = issueTokens(user);
		return LoginResponse.of(user, tokens.accessToken(), tokens.refreshToken());
	}

	/** 카카오 소셜로그인: code 교환 → 사용자 조회 → 기존 로그인 / 신규 자동가입. */
	@Transactional
	public KakaoLoginResponse kakaoLogin(String code, String redirectUri) {
		if (!StringUtils.hasText(code)) {
			throw new CustomException(ErrorCode.INVALID_KAKAO_CODE);
		}

		KakaoTokenResponse token = kakaoApiClient.getToken(code, redirectUri);
		KakaoUserResponse kakaoUser = kakaoApiClient.getUserInfo(token.accessToken());
		Long kakaoId = kakaoUser.id();

		// 탈퇴 회원은 제외하고 찾는다. 같은 카카오 계정으로 다시 들어오면 신규 가입으로 처리된다
		// (소셜은 로그인이 곧 가입이라, 탈퇴 행을 그대로 집어오면 재가입 경로가 아예 없어진다).
		User existing = userRepository.findByKakaoIdAndDeletedAtIsNull(kakaoId).orElse(null);
		boolean isNewUser = (existing == null);
		User user = isNewUser ? registerKakaoUser(kakaoUser) : existing;
		// 비즈 앱 전환 전에 가입한 회원은 자리표시자 이메일을 갖고 있어 알림 메일을 받을 수 없다.
		// 이제 카카오가 실제 이메일을 내려주므로 다시 로그인할 때 채워 넣는다.
		if (!isNewUser) {
			backfillPlaceholderEmail(user, kakaoUser.email());
		}

		TokenPair tokens = issueTokens(user);
		return KakaoLoginResponse.of(user, tokens.accessToken(), tokens.refreshToken(), isNewUser);
	}

	/** 구글 소셜로그인: code 교환 → 사용자 조회 → 기존 로그인 / 신규 자동가입. */
	@Transactional
	public SocialLoginResponse googleLogin(String code, String redirectUri) {
		if (!StringUtils.hasText(code)) {
			throw new CustomException(ErrorCode.INVALID_GOOGLE_CODE);
		}
		GoogleTokenResponse token = googleApiClient.getToken(code, redirectUri);
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
		// 카카오와 같은 이유로 탈퇴 회원을 제외하고 찾는다(재가입 = 신규 가입).
		User existing = userRepository.findByLoginTypeAndProviderIdAndDeletedAtIsNull(loginType, providerId)
				.orElse(null);
		boolean isNewUser = (existing == null);
		User user = isNewUser
				? registerSocialUser(loginType, providerId, email, name, emailPrefix)
				: existing;

		TokenPair tokens = issueTokens(user);
		return SocialLoginResponse.of(user, tokens.accessToken(), tokens.refreshToken(), isNewUser);
	}

	/**
	 * 자리표시자 이메일을 실제 이메일로 교체한다.
	 * 이미 실제 주소가 들어 있으면 건드리지 않는다(사용자가 마이페이지에서 바꿨을 수 있다).
	 */
	private void backfillPlaceholderEmail(User user, String providerEmail) {
		if (!StringUtils.hasText(providerEmail) || !isPlaceholderEmail(user.getEmail())) {
			return;
		}
		user.changeEmail(providerEmail);
		log.info("[Auth] 자리표시자 이메일을 실제 이메일로 교체 (userId={})", user.getId());
	}

	private boolean isPlaceholderEmail(String email) {
		return email != null && email.endsWith(PLACEHOLDER_EMAIL_DOMAIN);
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

		TokenPair tokens = issueTokens(user);
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
		// 시군구까지 올 수 있어 이름 해석을 RegionResolver 로 위임한다.
		// 특정하지 못하면 null 로 두고 가입은 계속한다(거주지역은 선택 입력).
		Region region = regionResolver.byName(request.region());
		UserProfile profile = UserProfile.builder()
				.user(user)
				.region(region)
				.birthDate(request.birthDate())
				.gender(request.gender())
				.nationality(request.nationality())
				.onboardingStep("STEP_1")
				.isOnboardingCompleted(false)
				.build();
		userProfileRepository.save(profile);
	}

	/**
	 * 로그인 아이디 정규화·검증. 대소문자 구분으로 생기는 혼동(Junho vs junho)을 막으려고
	 * 소문자로 낮춰 저장한다. 그래서 중복 검사도 같은 기준으로 걸린다.
	 */
	private String normalizeLoginId(String value) {
		if (!StringUtils.hasText(value)) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		String normalized = value.trim().toLowerCase();
		if (!LOGIN_ID_PATTERN.matcher(normalized).matches()) {
			throw new CustomException(ErrorCode.INVALID_LOGIN_ID_FORMAT);
		}
		return normalized;
	}

	/** 회원가입 화면의 아이디 "중복 확인" 버튼. 사용 가능하면 true. */
	@Transactional(readOnly = true)
	public boolean isLoginIdAvailable(String loginId) {
		return !userRepository.existsByLoginIdAndDeletedAtIsNull(normalizeLoginId(loginId));
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

	/**
	 * 카카오 신규 가입. 비즈 앱 전환으로 이메일이 필수 동의항목이 되어 실제 주소를 받을 수 있다.
	 *
	 * <p>예전에는 이메일이 없으면 {@code kakao_{id}@wishconnect.kr} 같은 자리표시자를 넣었는데,
	 * 그 주소로는 인증·알림 메일이 나가지 않아 계정이 사실상 연락 두절 상태가 됐다.
	 * 그래서 이메일을 못 받으면 자리표시자로 덮지 않고 가입을 막는다.
	 * (필수 동의항목이어도 카카오 계정에 이메일이 없거나 미인증이면 값이 비어 올 수 있다)
	 */
	private User registerKakaoUser(KakaoUserResponse kakaoUser) {
		Long kakaoId = kakaoUser.id();
		if (!StringUtils.hasText(kakaoUser.email())) {
			log.warn("[Auth] 카카오 이메일 미제공으로 가입 중단 (kakaoId={})", kakaoId);
			throw new CustomException(ErrorCode.KAKAO_EMAIL_REQUIRED);
		}
		String email = kakaoUser.email();
		User user = userRepository.save(User.createKakao(kakaoId, email, kakaoUser.nickname()));
		log.info("[Auth] 카카오 신규 회원 자동가입 (userId={}, kakaoId={})", user.getId(), kakaoId);
		return user;
	}

	/** Access/Refresh 토큰을 발급하고 Refresh Token 을 Redis 에 저장한다. */
	private TokenPair issueTokens(User user) {
		UUID userId = user.getId();
		String accessToken = jwtProvider.createAccessToken(userId, user.getRole().name());
		String refreshToken = jwtProvider.createRefreshToken(userId);
		refreshTokenService.save(userId, refreshToken);
		return new TokenPair(accessToken, refreshToken);
	}

	private record TokenPair(String accessToken, String refreshToken) {
	}
}
