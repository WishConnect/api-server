package com.wishconnect.domain.user.service;

import com.wishconnect.domain.common.entity.Major;
import com.wishconnect.domain.common.entity.MajorCategory;
import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.common.entity.School;
import com.wishconnect.domain.common.repository.MajorRepository;
import com.wishconnect.domain.common.repository.RegionRepository;
import com.wishconnect.domain.common.repository.SchoolRepository;
import com.wishconnect.domain.user.dto.request.ProfileAcademicRequest;
import com.wishconnect.domain.user.dto.request.ProfileBasicRequest;
import com.wishconnect.domain.user.dto.request.ProfileHouseholdRequest;
import com.wishconnect.domain.user.dto.response.OnboardingCompleteResponse;
import com.wishconnect.domain.user.dto.response.OnboardingStepResponse;
import com.wishconnect.domain.user.dto.response.ProfileResponse;
import com.wishconnect.domain.user.entity.EnrollmentStatus;
import com.wishconnect.domain.user.entity.FamilyCategory;
import com.wishconnect.domain.user.entity.FamilyType;
import com.wishconnect.domain.user.entity.Gender;
import com.wishconnect.domain.user.entity.Interest;
import com.wishconnect.domain.user.entity.Nationality;
import com.wishconnect.domain.user.entity.SecondMajorType;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.entity.UserFamilyType;
import com.wishconnect.domain.user.entity.UserInterest;
import com.wishconnect.domain.user.entity.UserProfile;
import com.wishconnect.domain.user.repository.FamilyTypeRepository;
import com.wishconnect.domain.user.repository.InterestRepository;
import com.wishconnect.domain.user.repository.UserFamilyTypeRepository;
import com.wishconnect.domain.user.repository.UserInterestRepository;
import com.wishconnect.domain.user.repository.UserProfileRepository;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/*
온보딩 단계별 입력값을 users, user_profile, user_family_type, user_interest에 저장하는 서비스입니다.
프론트 명세는 학교/전공/관심사를 문자열로 보내므로 이름 기준으로 저장합니다.
지역은 드롭다운 기반 마스터 데이터에서만 찾고, 학교/전공/가구/관심사는 초기 데이터가 없으면 생성합니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileService {

	private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+)");

	private final UserRepository userRepository;
	private final UserProfileRepository userProfileRepository;
	private final RegionRepository regionRepository;
	private final SchoolRepository schoolRepository;
	private final MajorRepository majorRepository;
	private final FamilyTypeRepository familyTypeRepository;
	private final InterestRepository interestRepository;
	private final UserFamilyTypeRepository userFamilyTypeRepository;
	private final UserInterestRepository userInterestRepository;

	// STEP 1: users의 기본 계정 표시 정보와 user_profile의 기본 조건 정보를 함께 갱신합니다.
	@Transactional
	public OnboardingStepResponse saveBasic(UUID userId, ProfileBasicRequest request) {
		User user = getUser(userId);
		UserProfile profile = getOrCreateProfile(user);
		Region region = getRegion(request.region());

		user.updateBasicProfile(request.name().trim(), request.phone().trim());
		profile.updateBasic(
				validateBirthDate(request.birthDate()),
				parseEnum(Gender.class, request.gender()),
				parseEnum(Nationality.class, request.nationality()),
				region
		);
		return new OnboardingStepResponse(1, true);
	}

	// STEP 2: 추천 매칭에 사용할 학교/전공/재학상태/학점 정보를 저장합니다.
	@Transactional
	public OnboardingStepResponse saveAcademic(UUID userId, ProfileAcademicRequest request) {
		User user = getUser(userId);
		UserProfile profile = getOrCreateProfile(user);

		School school = getOrCreateSchool(request.university());
		Major major = getOrCreateMajor(request.majorName(), request.majorCategory());
		profile.updateAcademic(
				school,
				major,
				parseEnum(EnrollmentStatus.class, request.enrollmentStatus()),
				normalizeRequired(request.grade()),
				request.semesterGpa(),
				request.cumulativeGpa(),
				parseSecondMajorType(request.dualMajor())
		);
		return new OnboardingStepResponse(2, true);
	}

	// STEP 3: 소득/가구/관심사 조건을 저장하고 기존 선택값은 새 요청값으로 교체합니다.
	@Transactional
	public OnboardingStepResponse saveHousehold(UUID userId, ProfileHouseholdRequest request) {
		User user = getUser(userId);
		UserProfile profile = getOrCreateProfile(user);
		profile.updateHousehold(
				parseIncomeLevel(request.incomeLevel()),
				request.familySize()
		);

		replaceFamilyTypes(profile, request.familyTypes(), request.personalStatuses());
		replaceInterests(profile, request.interests());
		return new OnboardingStepResponse(3, true);
	}

	// STEP 1~3을 끝낸 사용자만 온보딩 완료 처리합니다.
	@Transactional
	public OnboardingCompleteResponse complete(UUID userId) {
		User user = getUser(userId);
		UserProfile profile = getOrCreateProfile(user);
		if (onboardingStepOrder(profile.getOnboardingStep()) < 3) {
			throw new CustomException(ErrorCode.ONBOARDING_INCOMPLETE);
		}

		user.completeOnboarding();
		profile.completeOnboarding();
		return new OnboardingCompleteResponse(true);
	}

	// 마이페이지/온보딩 재진입 시 현재 저장된 프로필과 완성도를 조회합니다.
	@Transactional(readOnly = true)
	public ProfileResponse getProfile(UUID userId) {
		User user = getUser(userId);
		UserProfile profile = userProfileRepository.findByUserId(userId).orElse(null);
		List<UserFamilyType> familyMappings = userFamilyTypeRepository.findAllByUserProfile_User_Id(userId);
		List<String> familyTypes = familyMappings.stream()
				.filter(mapping -> mapping.getFamilyType().getCategory() == FamilyCategory.FAMILY)
				.map(mapping -> mapping.getFamilyType().getName())
				.toList();
		List<String> personalStatuses = familyMappings.stream()
				.filter(mapping -> mapping.getFamilyType().getCategory() == FamilyCategory.PERSONAL)
				.map(mapping -> mapping.getFamilyType().getName())
				.toList();
		List<String> interests = userInterestRepository.findAllByUserProfile_User_Id(userId)
				.stream()
				.map(mapping -> mapping.getInterest().getName())
				.toList();

		return new ProfileResponse(
				user.getId(),
				user.getName(),
				user.getEmail(),
				profile == null ? null : profile.getBirthDate(),
				user.getPhone(),
				profile == null || profile.getGender() == null ? null : profile.getGender().name(),
				profile == null || profile.getNationality() == null ? null : profile.getNationality().name(),
				profile == null || profile.getRegion() == null ? null : profile.getRegion().getName(),
				calculateCompletionRate(user, profile, familyTypes, personalStatuses, interests),
				user.isOnboardingCompleted(),
				toAcademic(profile),
				toHousehold(profile, familyTypes, personalStatuses),
				interests
		);
	}

	private User getUser(UUID userId) {
		return userRepository.findById(userId)
				.orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
	}

	private UserProfile getOrCreateProfile(User user) {
		return userProfileRepository.findByUserId(user.getId())
				.orElseGet(() -> userProfileRepository.save(UserProfile.createFor(user)));
	}

	private Region getRegion(String name) {
		String normalized = normalizeRegionName(name);
		return regionRepository.findByName(normalized)
				.orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT));
	}

	private School getOrCreateSchool(String name) {
		String normalized = normalizeRequired(name);
		return schoolRepository.findFirstByName(normalized)
				.orElseGet(() -> schoolRepository.save(School.builder().name(normalized).build()));
	}

	/**
	 * 요청한 (전공명, 계열) 조합의 전공을 찾거나 만든다.
	 *
	 * <p>이전에는 전공명만으로 조회해, 이름이 이미 있으면 요청의 계열을 조용히 버렸다.
	 * 계열은 추천 매칭에 쓰이는 값이라 사용자가 고른 값이 반영돼야 한다. 따라서
	 * (이름+계열) 조합을 먼저 찾고, 계열이 비어 있던 기존 행이면 그 값을 채운다.
	 * 이름은 같은데 계열이 다르면 사용자가 고른 계열로 새 행을 만들되 경고 로그를 남긴다.
	 */
	private Major getOrCreateMajor(String name, String category) {
		String normalizedName = normalizeRequired(name);
		MajorCategory majorCategory = MajorCategory.fromRequired(category);

		Major exactMatch = majorRepository.findFirstByNameAndCategory(normalizedName, majorCategory)
				.orElse(null);
		if (exactMatch != null) {
			return exactMatch;
		}

		Major sameName = majorRepository.findFirstByName(normalizedName).orElse(null);
		if (sameName != null && sameName.getCategory() == null) {
			sameName.fillCategoryIfAbsent(majorCategory);
			return sameName;
		}
		if (sameName != null) {
			log.warn("Major category mismatch. name={}, master={}, requested={}",
					normalizedName, sameName.getCategory(), majorCategory);
		}
		return majorRepository.save(Major.builder()
				.name(normalizedName)
				.category(majorCategory)
				.build());
	}

	private void replaceFamilyTypes(UserProfile profile, List<String> familyNames, List<String> personalNames) {
		userFamilyTypeRepository.deleteByUserProfile(profile);
		userFamilyTypeRepository.flush();

		List<UserFamilyType> mappings = new ArrayList<>();
		mappings.addAll(toFamilyTypeMappings(profile, familyNames, FamilyCategory.FAMILY));
		mappings.addAll(toFamilyTypeMappings(profile, personalNames, FamilyCategory.PERSONAL));
		userFamilyTypeRepository.saveAll(mappings);
	}

	private List<UserFamilyType> toFamilyTypeMappings(UserProfile profile, List<String> names, FamilyCategory category) {
		return normalizeSelections(names).stream()
				.map(name -> getOrCreateFamilyType(name, category))
				.map(familyType -> UserFamilyType.builder()
						.userProfile(profile)
						.familyType(familyType)
						.build())
				.toList();
	}

	private FamilyType getOrCreateFamilyType(String name, FamilyCategory category) {
		return familyTypeRepository.findFirstByNameAndCategory(name, category)
				.orElseGet(() -> familyTypeRepository.save(FamilyType.builder()
						.name(name)
						.category(category)
						.build()));
	}

	private void replaceInterests(UserProfile profile, List<String> names) {
		userInterestRepository.deleteByUserProfile(profile);
		userInterestRepository.flush();

		List<UserInterest> mappings = normalizeSelections(names).stream()
				.map(this::getOrCreateInterest)
				.map(interest -> UserInterest.builder()
						.userProfile(profile)
						.interest(interest)
						.build())
				.toList();
		userInterestRepository.saveAll(mappings);
	}

	private Interest getOrCreateInterest(String name) {
		return interestRepository.findFirstByName(name)
				.orElseGet(() -> interestRepository.save(Interest.builder().name(name).build()));
	}

	private List<String> normalizeSelections(List<String> values) {
		if (values == null) {
			return List.of();
		}
		Set<String> uniqueValues = new LinkedHashSet<>();
		values.stream()
				.filter(StringUtils::hasText)
				.map(String::trim)
				.forEach(uniqueValues::add);
		return new ArrayList<>(uniqueValues);
	}

	private <T extends Enum<T>> T parseEnum(Class<T> enumType, String value) {
		try {
			return Enum.valueOf(enumType, normalizeRequired(value));
		} catch (IllegalArgumentException exception) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
	}

	private SecondMajorType parseSecondMajorType(String value) {
		if (!StringUtils.hasText(value) || "null".equalsIgnoreCase(value.trim())) {
			return null;
		}
		String normalized = value.trim()
				.toUpperCase();
		return switch (normalized) {
			case "DOUBLE" -> SecondMajorType.DOUBLE;
			case "MINOR" -> SecondMajorType.MINOR;
			default -> throw new CustomException(ErrorCode.INVALID_INPUT);
		};
	}

	private Integer parseIncomeLevel(String value) {
		String normalized = normalizeRequired(value);
		if (isUnknownIncomeLevel(normalized)) {
			return null;
		}
		Matcher matcher = FIRST_NUMBER.matcher(normalized);
		if (!matcher.find()) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		return Integer.parseInt(matcher.group(1));
	}

	private boolean isUnknownIncomeLevel(String value) {
		return switch (value.trim().toUpperCase()) {
			case "모르겠어요", "모름", "UNKNOWN", "UNKNOWN_INCOME" -> true;
			default -> false;
		};
	}

	/**
	 * 생년월일 검증. 미래 날짜와 비현실적으로 오래된 값만 막는다.
	 * 대학생 서비스지만 나이 하한을 두지는 않는다 — 검정고시·만학도 등 예외가 실제로 있다.
	 */
	private LocalDate validateBirthDate(LocalDate value) {
		if (value == null || value.isAfter(LocalDate.now()) || value.isBefore(LocalDate.of(1900, 1, 1))) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		return value;
	}

	private String normalizeRequired(String value) {
		if (!StringUtils.hasText(value)) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		return value.trim();
	}

	private String normalizeRegionName(String value) {
		String normalized = normalizeRequired(value);
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

	private ProfileResponse.Academic toAcademic(UserProfile profile) {
		if (profile == null) {
			return null;
		}
		return new ProfileResponse.Academic(
				profile.getSchool() == null ? null : profile.getSchool().getName(),
				profile.getMajor() == null ? null : profile.getMajor().getCategory(),
				profile.getMajor() == null ? null : profile.getMajor().getName(),
				profile.getEnrollmentStatus() == null ? null : profile.getEnrollmentStatus().name(),
				profile.getGrade(),
				profile.getSemesterGpa(),
				profile.getCumulativeGpa(),
				toDualMajorResponse(profile.getSecondMajorType())
		);
	}

	private ProfileResponse.Household toHousehold(
			UserProfile profile,
			List<String> familyTypes,
			List<String> personalStatuses
	) {
		if (profile == null) {
			return null;
		}
		return new ProfileResponse.Household(
				profile.getIncomeLevel() == null ? null : profile.getIncomeLevel() + "분위",
				profile.getFamilySize(),
				familyTypes,
				personalStatuses
		);
	}

	private String toDualMajorResponse(SecondMajorType secondMajorType) {
		if (secondMajorType == null) {
			return null;
		}
		return secondMajorType.name();
	}

	private int onboardingStepOrder(String step) {
		if (!StringUtils.hasText(step)) {
			return 0;
		}
		return switch (step) {
			case "STEP_1" -> 1;
			case "STEP_2" -> 2;
			case "STEP_3" -> 3;
			case "STEP_4" -> 4;
			default -> 0;
		};
	}

	private int calculateCompletionRate(
			User user,
			UserProfile profile,
			List<String> familyTypes,
			List<String> personalStatuses,
			List<String> interests
	) {
		List<Object> fields = new ArrayList<>();
		fields.add(user.getName());
		fields.add(user.getPhone());
		if (profile != null) {
			fields.add(profile.getBirthDate());
			fields.add(profile.getGender());
			fields.add(profile.getNationality());
			fields.add(profile.getRegion());
			fields.add(profile.getSchool());
			fields.add(profile.getMajor());
			fields.add(profile.getEnrollmentStatus());
			fields.add(profile.getGrade());
			fields.add(profile.getSemesterGpa());
			fields.add(profile.getCumulativeGpa());
			fields.add(profile.getIncomeLevel());
			fields.add(profile.getFamilySize());
		}
		fields.add(familyTypes.isEmpty() && personalStatuses.isEmpty() ? null : Boolean.TRUE);
		fields.add(interests.isEmpty() ? null : Boolean.TRUE);

		long filled = fields.stream().filter(value -> value != null && !value.toString().isBlank()).count();
		return (int) Math.round((filled * 100.0) / fields.size());
	}
}
