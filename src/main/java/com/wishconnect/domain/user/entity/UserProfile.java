package com.wishconnect.domain.user.entity;

import com.wishconnect.domain.common.entity.Major;
import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.common.entity.School;
import com.wishconnect.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "user_profile")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class UserProfile extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 프로필 소유 사용자 (1:1) */
	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false, unique = true)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "school_id")
	private School school;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "major_id")
	private Major major;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "region_id")
	private Region region;

	@Column(name = "birth_year", length = 4)
	private String birthYear;

	@Enumerated(EnumType.STRING)
	@Column(length = 10)
	private Gender gender;

	@Enumerated(EnumType.STRING)
	@Column(length = 10)
	private Nationality nationality;

	@Enumerated(EnumType.STRING)
	@Column(name = "enrollment_status", length = 20)
	private EnrollmentStatus enrollmentStatus;

	@Column(length = 20)
	private String grade;

	@Column(name = "semester_gpa", precision = 3, scale = 2)
	private BigDecimal semesterGpa;

	@Column(name = "cumulative_gpa", precision = 3, scale = 2)
	private BigDecimal cumulativeGpa;

	/** 복수전공/부전공 구분. 해당 없으면 null */
	@Enumerated(EnumType.STRING)
	@Column(name = "dual_major", length = 10)
	private SecondMajorType secondMajorType;

	@Column(name = "income_level")
	private Integer incomeLevel;

	@Column(name = "family_size")
	private Long familySize;

	/** 온보딩에서 마지막으로 완료한 단계입니다. 중간 이탈 사용자의 재진입 위치를 판단할 때 사용합니다. */
	@Column(name = "onboarding_step", length = 20)
	private String onboardingStep;

	/** 추천/매칭에 사용할 수 있을 만큼 온보딩이 끝났는지 표시합니다. */
	@Column(name = "is_onboarding_completed")
	private boolean isOnboardingCompleted;

	/** 회원가입 직후 또는 온보딩 첫 저장 시 비어 있는 프로필을 생성합니다. */
	public static UserProfile createFor(User user) {
		UserProfile profile = new UserProfile();
		profile.user = user;
		profile.onboardingStep = "STEP_1";
		profile.isOnboardingCompleted = false;
		return profile;
	}

	public void updateBasic(String birthYear, Gender gender, Nationality nationality, Region region) {
		this.birthYear = birthYear;
		this.gender = gender;
		this.nationality = nationality;
		this.region = region;
		advanceOnboardingStep("STEP_1");
	}

	public void updateAcademic(
			School school,
			Major major,
			EnrollmentStatus enrollmentStatus,
			String grade,
			BigDecimal semesterGpa,
			BigDecimal cumulativeGpa,
			SecondMajorType secondMajorType
	) {
		this.school = school;
		this.major = major;
		this.enrollmentStatus = enrollmentStatus;
		this.grade = grade;
		this.semesterGpa = semesterGpa;
		this.cumulativeGpa = cumulativeGpa;
		this.secondMajorType = secondMajorType;
		advanceOnboardingStep("STEP_2");
	}

	public void updateHousehold(Integer incomeLevel, Long familySize) {
		this.incomeLevel = incomeLevel;
		this.familySize = familySize;
		advanceOnboardingStep("STEP_3");
	}

	public void completeOnboarding() {
		this.isOnboardingCompleted = true;
		this.onboardingStep = "STEP_4";
	}

	private void advanceOnboardingStep(String nextStep) {
		if (stepOrder(nextStep) > stepOrder(this.onboardingStep)) {
			this.onboardingStep = nextStep;
		}
	}

	private int stepOrder(String step) {
		if (step == null) {
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
}
