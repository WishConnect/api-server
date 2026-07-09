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

	@Column
	private Integer birthYear;

	@Enumerated(EnumType.STRING)
	@Column
	private Gender gender;

	@Enumerated(EnumType.STRING)
	@Column
	private Nationality nationality;

	@Enumerated(EnumType.STRING)
	@Column
	private EnrollmentStatus enrollmentStatus;

	@Column
	private Integer grade;

	@Column(precision = 3, scale = 2)
	private BigDecimal semesterGpa;

	@Column(precision = 3, scale = 2)
	private BigDecimal cumulativeGpa;

	/** 복수전공/부전공 구분. 해당 없으면 null */
	@Enumerated(EnumType.STRING)
	@Column
	private SecondMajorType secondMajorType;

	@Column
	private Integer incomeLevel;

	@Column
	private Integer onboardingStep;

	@Column
	private boolean isOnboardingCompleted;
}
