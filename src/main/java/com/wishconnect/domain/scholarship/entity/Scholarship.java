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
public class Scholarship extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 500)
	private String title;

	@Column(length = 200)
	private String provider;

	@Column(columnDefinition = "TEXT")
	private String summary;

	@Column(columnDefinition = "TEXT")
	private String description;

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

	@Column(name = "selection_count")
	private Integer selectionCount;

	private Long amount;

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

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	@Column(name = "homepage_url", length = 1000)
	private String homepageUrl;

	/**
	 * 장학금 상세페이지. 공공데이터 원문에는 이 필드가 없고 {@code homepageUrl} 이 기관 메인이라,
	 * 검색으로 찾아 채운다({@code ScholarshipEnrichmentService}). 사람이 확인해 넣기도 한다.
	 */
	@Column(name = "detail_url", length = 1000)
	private String detailUrl;

	/** 자동 보완을 마지막으로 시도한 시각. 매 배치마다 같은 건을 다시 검색하지 않으려고 둔다. */
	@Column(name = "enriched_at")
	private LocalDateTime enrichedAt;

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
		// 동기화 피드에 다시 들어왔다는 것은 살아 있는 공고라는 뜻이므로 소프트 삭제를 해제한다.
		// 물리 삭제 시절에는 행이 사라졌다가 새로 생겨 자연히 되살아났는데,
		// 소프트 삭제로 바꾼 뒤에는 여기서 풀어주지 않으면 deletedAt 이 남아 영원히 노출되지 않는다.
		// (조회 쿼리가 전부 deletedAt IS NULL 로 거른다)
		this.deletedAt = null;
		this.lastSyncedAt = LocalDateTime.now();
	}

	/** 자동 보완 결과 반영. 상세 URL 을 못 찾았어도 시도 시각은 남겨 재시도 주기를 지킨다. */
	public void applyEnrichment(String detailUrl) {
		if (org.springframework.util.StringUtils.hasText(detailUrl)) {
			this.detailUrl = detailUrl;
		}
		this.enrichedAt = LocalDateTime.now();
	}

	public void updateActive(boolean active) {
		this.active = active;
	}

	/** 수기 등록분의 primary_source. 동기화 배치가 건드리지 않는 출처임을 구분한다. */
	public static final String MANUAL_SOURCE = "MANUAL";

	/**
	 * 관리자가 직접 등록한 장학금.
	 *
	 * <p>{@code dedupKey} 는 공공데이터 응답으로 만드는 키와 절대 겹치지 않는 형식을 쓴다.
	 * 겹치면 다음 동기화 때 {@link #updateFromApi} 로 덮여 수기 입력이 날아간다.
	 * 사람이 확인하고 넣은 값이므로 {@code verified} 는 참으로 시작한다.
	 */
	public static Scholarship createManual(
		String title,
		String provider,
		String summary,
		String description,
		ScholarshipType scholarshipType,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		Integer selectionCount,
		Long amount,
		String homepageUrl,
		String dedupKey
	) {
		Scholarship scholarship = Scholarship.builder()
			.title(title)
			.provider(provider)
			.summary(summary)
			.description(description)
			.scholarshipType(scholarshipType)
			.applicationStartAt(applicationStartAt)
			.applicationEndAt(applicationEndAt)
			.recruitmentStatus(resolveStatus(applicationStartAt, applicationEndAt))
			.selectionCount(selectionCount)
			.amount(amount)
			.primarySource(MANUAL_SOURCE)
			.dedupKey(dedupKey)
			.homepageUrl(homepageUrl)
			.build();
		scholarship.verified = true;
		return scholarship;
	}

	/**
	 * 관리자 직접 수정. null 인 필드는 기존 값을 유지해 부분 수정이 가능하다.
	 * (오등록 신고가 들어온 항목의 한두 필드만 고치는 게 주 용도라 전체 교체는 오히려 위험하다)
	 */
	public void updateByAdmin(
		String title,
		String provider,
		String summary,
		String description,
		ScholarshipType scholarshipType,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		Integer selectionCount,
		Long amount,
		String homepageUrl
	) {
		if (title != null) {
			this.title = title;
		}
		if (provider != null) {
			this.provider = provider;
		}
		if (summary != null) {
			this.summary = summary;
		}
		if (description != null) {
			this.description = description;
		}
		if (scholarshipType != null) {
			this.scholarshipType = scholarshipType;
		}
		if (applicationStartAt != null) {
			this.applicationStartAt = applicationStartAt;
		}
		if (applicationEndAt != null) {
			this.applicationEndAt = applicationEndAt;
		}
		if (selectionCount != null) {
			this.selectionCount = selectionCount;
		}
		if (amount != null) {
			this.amount = amount;
		}
		if (homepageUrl != null) {
			this.homepageUrl = homepageUrl;
		}
		this.recruitmentStatus = resolveStatus(this.applicationStartAt, this.applicationEndAt);
		this.active = this.recruitmentStatus != RecruitmentStatus.CLOSED;
		// 사람이 확인해 고친 값이므로 검증된 것으로 표시한다.
		this.verified = true;
	}

	/**
	 * 대학 장학공지를 LLM 으로 재파싱한 결과를 덮어쓴다.
	 *
	 * <p>{@link #updateFromApi} 와 달리 공공데이터 응답이 아니라 공고 본문 파싱 결과를 받는다.
	 * 기존 값이 정규식으로 잘못 파싱된 것일 수 있으므로 <b>null 도 그대로 덮어쓴다.</b>
	 * 예를 들어 근무기간을 신청기간으로 잘못 넣어둔 행은, 재파싱에서 기간을 찾지 못하면
	 * null 로 비워야 맞다. null 을 무시하면 잘못된 옛 값이 영구히 남는다.
	 *
	 * <p>{@code verified} 는 건드리지 않는다. 사람이 검수한 표시를 기계 파싱이 되돌리면 안 된다.
	 */
	public void applyLlmParsed(
		String title,
		String provider,
		String summary,
		String description,
		ScholarshipType scholarshipType,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		Integer selectionCount,
		Long amount,
		String homepageUrl
	) {
		this.title = title;
		this.provider = provider;
		this.summary = summary;
		this.description = description;
		this.scholarshipType = scholarshipType;
		this.applicationStartAt = applicationStartAt;
		this.applicationEndAt = applicationEndAt;
		this.selectionCount = selectionCount;
		this.amount = amount;
		if (homepageUrl != null) {
			this.homepageUrl = homepageUrl;
		}
		this.recruitmentStatus = resolveStatus(applicationStartAt, applicationEndAt);
		// 마감된 공고는 목록에서 내린다. 마감일을 못 찾은 경우(null)는 노출을 유지한다 —
		// 기간을 모른다는 것이 끝났다는 뜻은 아니고, 숨기는 쪽이 더 해롭다.
		this.active = this.recruitmentStatus != RecruitmentStatus.CLOSED;
		this.deletedAt = null;
		this.lastSyncedAt = LocalDateTime.now();
	}

	/** 오등록으로 확인된 장학금을 목록에서 내린다. 이력 추적을 위해 행은 남긴다. */
	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
		this.active = false;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	private static RecruitmentStatus resolveStatus(LocalDateTime startAt, LocalDateTime endAt) {
		LocalDateTime now = LocalDateTime.now();
		if (endAt != null && endAt.isBefore(now)) {
			return RecruitmentStatus.CLOSED;
		}
		if (startAt != null && startAt.isAfter(now)) {
			return RecruitmentStatus.UPCOMING;
		}
		return RecruitmentStatus.OPEN;
	}
}
