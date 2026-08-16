package com.wishconnect.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.common.entity.Major;
import com.wishconnect.domain.common.entity.MajorCategory;
import com.wishconnect.domain.common.entity.School;
import com.wishconnect.domain.common.repository.MajorRepository;
import com.wishconnect.domain.common.repository.RegionRepository;
import com.wishconnect.domain.common.repository.SchoolRepository;
import com.wishconnect.domain.user.dto.request.ProfileAcademicRequest;
import com.wishconnect.domain.user.dto.request.ProfileHouseholdRequest;
import com.wishconnect.domain.user.dto.response.OnboardingCompleteResponse;
import com.wishconnect.domain.user.entity.SecondMajorType;
import com.wishconnect.domain.user.entity.User;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

	@Mock
	private UserRepository userRepository;
	@Mock
	private UserProfileRepository userProfileRepository;
	@Mock
	private RegionRepository regionRepository;
	@Mock
	private SchoolRepository schoolRepository;
	@Mock
	private MajorRepository majorRepository;
	@Mock
	private FamilyTypeRepository familyTypeRepository;
	@Mock
	private InterestRepository interestRepository;
	@Mock
	private UserFamilyTypeRepository userFamilyTypeRepository;
	@Mock
	private UserInterestRepository userInterestRepository;

	private UserProfileService userProfileService;
	private UUID userId;
	private User user;
	private UserProfile profile;

	@BeforeEach
	void setUp() {
		userProfileService = new UserProfileService(
				userRepository,
				userProfileRepository,
				regionRepository,
				schoolRepository,
				majorRepository,
				familyTypeRepository,
				interestRepository,
				userFamilyTypeRepository,
				userInterestRepository
		);
		userId = UUID.randomUUID();
		user = User.createLocal("user@example.com", "user01", "encoded", "홍길동", "010-1234-5678");
		ReflectionTestUtils.setField(user, "id", userId);
		profile = UserProfile.createFor(user);

		given(userRepository.findById(userId)).willReturn(Optional.of(user));
		given(userProfileRepository.findByUserId(userId)).willReturn(Optional.of(profile));
	}

	@Test
	@DisplayName("학적 정보는 ERD 컬럼 타입에 맞게 문자열 학년과 dualMajor enum으로 저장한다")
	void saveAcademic() {
		School school = School.builder().name("건국대학교").build();
		Major major = Major.builder().name("컴퓨터공학").category(MajorCategory.ENGINEERING).build();
		given(schoolRepository.findFirstByName("건국대학교")).willReturn(Optional.empty());
		given(schoolRepository.save(any(School.class))).willReturn(school);
		given(majorRepository.findFirstByNameAndCategory("컴퓨터공학", MajorCategory.ENGINEERING))
				.willReturn(Optional.empty());
		given(majorRepository.findFirstByName("컴퓨터공학")).willReturn(Optional.empty());
		given(majorRepository.save(any(Major.class))).willReturn(major);

		userProfileService.saveAcademic(userId, new ProfileAcademicRequest(
				"건국대학교",
				"공학계열",
				"컴퓨터공학",
				"ENROLLED",
				"3학년 1학기",
				new BigDecimal("3.80"),
				new BigDecimal("3.60"),
				"DOUBLE"
		));

		assertThat(profile.getSchool()).isEqualTo(school);
		assertThat(profile.getMajor()).isEqualTo(major);
		assertThat(profile.getGrade()).isEqualTo("3학년 1학기");
		assertThat(profile.getSecondMajorType()).isEqualTo(SecondMajorType.DOUBLE);
		assertThat(profile.getOnboardingStep()).isEqualTo("STEP_2");
	}

	@Test
	@DisplayName("복수전공/부전공 값은 DOUBLE, MINOR, null만 허용한다")
	void saveAcademicAcceptsDualMajorEnumValues() {
		School school = School.builder().name("건국대학교").build();
		Major major = Major.builder().name("컴퓨터공학").category(MajorCategory.ENGINEERING).build();
		given(schoolRepository.findFirstByName("건국대학교")).willReturn(Optional.of(school));
		given(majorRepository.findFirstByNameAndCategory("컴퓨터공학", MajorCategory.ENGINEERING))
				.willReturn(Optional.of(major));

		userProfileService.saveAcademic(userId, new ProfileAcademicRequest(
				"건국대학교",
				"공학계열",
				"컴퓨터공학",
				"ENROLLED",
				"3학년 1학기",
				new BigDecimal("3.80"),
				new BigDecimal("3.60"),
				"DOUBLE"
		));
		assertThat(profile.getSecondMajorType()).isEqualTo(SecondMajorType.DOUBLE);

		userProfileService.saveAcademic(userId, new ProfileAcademicRequest(
				"건국대학교",
				"공학계열",
				"컴퓨터공학",
				"ENROLLED",
				"3학년 1학기",
				new BigDecimal("3.80"),
				new BigDecimal("3.60"),
				"MINOR"
		));
		assertThat(profile.getSecondMajorType()).isEqualTo(SecondMajorType.MINOR);

		userProfileService.saveAcademic(userId, new ProfileAcademicRequest(
				"건국대학교",
				"공학계열",
				"컴퓨터공학",
				"ENROLLED",
				"3학년 1학기",
				new BigDecimal("3.80"),
				new BigDecimal("3.60"),
				null
		));
		assertThat(profile.getSecondMajorType()).isNull();
	}

	@Test
	@DisplayName("6종에 없는 전공 계열은 INVALID_MAJOR_CATEGORY로 막는다")
	void saveAcademic_rejectsUnknownMajorCategory() {
		assertThatThrownBy(() -> userProfileService.saveAcademic(userId, academicRequest("공학")))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_MAJOR_CATEGORY);
	}

	@Test
	@DisplayName("전공명이 이미 있어도 계열이 다르면 요청 계열을 버리지 않는다")
	void saveAcademic_keepsRequestedCategoryWhenMasterDiffers() {
		Major masterMajor = Major.builder().name("컴퓨터공학").category(MajorCategory.ENGINEERING).build();
		Major createdMajor = Major.builder().name("컴퓨터공학").category(MajorCategory.NATURAL_SCIENCE).build();
		given(schoolRepository.findFirstByName("건국대학교")).willReturn(Optional.empty());
		given(schoolRepository.save(any(School.class))).willReturn(School.builder().name("건국대학교").build());
		given(majorRepository.findFirstByNameAndCategory("컴퓨터공학", MajorCategory.NATURAL_SCIENCE))
				.willReturn(Optional.empty());
		given(majorRepository.findFirstByName("컴퓨터공학")).willReturn(Optional.of(masterMajor));
		given(majorRepository.save(any(Major.class))).willReturn(createdMajor);

		userProfileService.saveAcademic(userId, academicRequest("자연과학계열"));

		assertThat(profile.getMajor().getCategory()).isEqualTo(MajorCategory.NATURAL_SCIENCE);
	}

	@Test
	@DisplayName("계열이 비어 있던 기존 전공은 요청 계열로 채우고 새로 만들지 않는다")
	void saveAcademic_fillsMissingCategoryOnExistingMajor() {
		Major legacyMajor = Major.builder().name("컴퓨터공학").build();
		given(schoolRepository.findFirstByName("건국대학교")).willReturn(Optional.empty());
		given(schoolRepository.save(any(School.class))).willReturn(School.builder().name("건국대학교").build());
		given(majorRepository.findFirstByNameAndCategory("컴퓨터공학", MajorCategory.ENGINEERING))
				.willReturn(Optional.empty());
		given(majorRepository.findFirstByName("컴퓨터공학")).willReturn(Optional.of(legacyMajor));

		userProfileService.saveAcademic(userId, academicRequest("공학계열"));

		assertThat(profile.getMajor()).isSameAs(legacyMajor);
		assertThat(legacyMajor.getCategory()).isEqualTo(MajorCategory.ENGINEERING);
		verify(majorRepository, never()).save(any(Major.class));
	}

	private ProfileAcademicRequest academicRequest(String majorCategory) {
		return new ProfileAcademicRequest(
				"건국대학교",
				majorCategory,
				"컴퓨터공학",
				"ENROLLED",
				"3학년 1학기",
				new BigDecimal("3.80"),
				new BigDecimal("3.60"),
				"DOUBLE"
		);
	}

	@Test
	@DisplayName("STEP3 완료 전 complete 요청은 ONBOARDING_INCOMPLETE로 실패한다")
	void completeBeforeHousehold() {
		assertThatThrownBy(() -> userProfileService.complete(userId))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.ONBOARDING_INCOMPLETE);
	}

	@Test
	@DisplayName("소득분위를 모르겠어요로 선택하면 null로 저장한다")
	void saveHousehold_acceptsUnknownIncomeLevel() {
		userProfileService.saveHousehold(userId, new ProfileHouseholdRequest(
				"모르겠어요",
				4L,
				List.of(),
				List.of(),
				List.of()
		));

		assertThat(profile.getIncomeLevel()).isNull();
		assertThat(profile.getOnboardingStep()).isEqualTo("STEP_3");
		verify(userFamilyTypeRepository).deleteByUser(user);
		verify(userInterestRepository).deleteByUser(user);
	}

	@Test
	@DisplayName("STEP3 완료 후 complete 요청은 온보딩 완료 상태로 변경한다")
	void completeAfterHousehold() {
		userProfileService.saveHousehold(userId, new ProfileHouseholdRequest(
				"3분위",
				4L,
				List.of(),
				List.of(),
				List.of()
		));

		OnboardingCompleteResponse response = userProfileService.complete(userId);

		assertThat(response.onboardingCompleted()).isTrue();
		assertThat(user.isOnboardingCompleted()).isTrue();
		assertThat(profile.isOnboardingCompleted()).isTrue();
		assertThat(profile.getOnboardingStep()).isEqualTo("STEP_4");
		verify(userFamilyTypeRepository).deleteByUser(user);
		verify(userInterestRepository).deleteByUser(user);
	}
}
