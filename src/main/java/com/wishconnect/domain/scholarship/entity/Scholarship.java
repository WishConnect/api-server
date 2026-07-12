package com.wishconnect.domain.scholarship.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
서비스에서 실제 조회/추천에 사용할 정제된 장학금 엔티티입니다.
raw_scholarship의 원본 JSON을 파싱한 뒤 제목, 기관, 신청기간, 금액 등을 이 테이블에 저장합니다.
 */
@Getter
@Entity
@Table(name = "scholarship")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Scholarship {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 500)
	private String title;

	@Column(length = 200)
	private String provider;

    //지원관련 요약
	@Column(columnDefinition = "TEXT")
	private String summary;

    @Column(columnDefinition = "TEXT")
	private String description;

    //교내,외부 장학금 구분
	@Enumerated(EnumType.STRING)
	@Column(name = "scholarship_type", length = 20)
	private ScholarshipType scholarshipType;

	@Column(name = "application_start_at")
	private LocalDateTime applicationStartAt;

	@Column(name = "application_end_at")
	private LocalDateTime applicationEndAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "recruitment_status", length = 20)
	private RecruitmentStatus recruitmentStatus;

    //선발 인원
	@Column(name = "selection_count")
	private Integer selectionCount;

    //지원 금액 ex) ○ 연간 500만원 (최대 7학기/ 1학년 2학기 부터)
	private Long amount;

    //기한 넘었는지 안넘었는지 확인하는부분 입니다.
	@Column(name = "is_active", nullable = false)
	private boolean active;

	@Column(name = "is_verified", nullable = false)
	private boolean verified;

	@Column(name = "primary_source", length = 50)
	private String primarySource;

	/*
	같은 장학금이 여러 월별 API 엔드포인트에 중복으로 들어오는 경우가 있어,
	상품명/운영기관/모집기간을 기준으로 만든 키로 정제 테이블 중복 저장을 막습니다.
	 */
	@Column(name = "dedup_key", length = 64, unique = true)
	private String dedupKey;

	@Column(name = "last_synced_at")
	private LocalDateTime lastSyncedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	@Column(name = "homepage_url", length = 1000)
	private String homepageUrl;

	@Builder
	private Scholarship(
		String title,
		String provider,
		String summary,
		String description,
		ScholarshipType scholarshipType,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		RecruitmentStatus recruitmentStatus,
		Integer selectionCount,
		Long amount,
		String primarySource,
		String dedupKey,
		String homepageUrl
	) {
		this.title = title;
		this.provider = provider;
		this.summary = summary;
		this.description = description;
		this.scholarshipType = scholarshipType;
		this.applicationStartAt = applicationStartAt;
		this.applicationEndAt = applicationEndAt;
		this.recruitmentStatus = recruitmentStatus == null ? RecruitmentStatus.UPCOMING : recruitmentStatus;
		this.selectionCount = selectionCount;
		this.amount = amount;
		this.active = true;
		this.verified = false;
		this.primarySource = primarySource;
		this.dedupKey = dedupKey;
		this.homepageUrl = homepageUrl;
		this.lastSyncedAt = LocalDateTime.now();
	}

	public void updateFromApi(
		String title,
		String provider,
		String summary,
		String description,
		ScholarshipType scholarshipType,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		RecruitmentStatus recruitmentStatus,
		Integer selectionCount,
		Long amount,
		String primarySource,
		String dedupKey,
		String homepageUrl
	) {
		this.title = title;
		this.provider = provider;
		this.summary = summary;
		this.description = description;
		this.scholarshipType = scholarshipType;
		this.applicationStartAt = applicationStartAt;
		this.applicationEndAt = applicationEndAt;
		this.recruitmentStatus = recruitmentStatus == null ? RecruitmentStatus.UPCOMING : recruitmentStatus;
		this.selectionCount = selectionCount;
		this.amount = amount;
		this.primarySource = primarySource;
		this.dedupKey = dedupKey;
		this.homepageUrl = homepageUrl;
		this.lastSyncedAt = LocalDateTime.now();
	}

	public void updateActive(boolean active) {
		this.active = active;
	}

	@PrePersist
	void prePersist() {
		LocalDateTime now = LocalDateTime.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	void preUpdate() {
		this.updatedAt = LocalDateTime.now();
	}
}
