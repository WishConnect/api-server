package com.wishconnect.domain.scholarship.entity;

import com.wishconnect.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "scholarship")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Scholarship extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	private String provider;

	@Column(columnDefinition = "TEXT")
	private String summary;

	@Column(columnDefinition = "TEXT")
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ScholarshipType scholarshipType;

	@Column
	private LocalDateTime applicationStartAt;

	@Column
	private LocalDateTime applicationEndAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private RecruitmentStatus recruitmentStatus;

	@Column
	private Integer selectionCount;

	@Column
	private Long amount;

	@Column(nullable = false)
	private boolean isActive;

	@Column(nullable = false)
	private boolean isVerified;

	@Column
	private String primarySource;

	@Column
	private LocalDateTime lastSyncedAt;

	/** 소프트 삭제 시각 (null 이면 미삭제) */
	@Column
	private LocalDateTime deletedAt;
}
