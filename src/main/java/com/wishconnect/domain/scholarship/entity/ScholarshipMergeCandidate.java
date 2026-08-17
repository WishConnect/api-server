package com.wishconnect.domain.scholarship.entity;

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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 중복으로 판단된 장학금 쌍. 사람이 승인해야 실제 병합이 일어난다.
 *
 * <p>{@code primary} 는 남길 쪽, {@code duplicate} 는 소프트 삭제할 쪽이다.
 * 병합하면 duplicate 를 참조하던 스크랩·자소서·신고 등이 primary 로 옮겨간다.
 *
 * <p>같은 쌍이 배치마다 다시 올라오지 않도록 (primary, duplicate) 에 유니크를 걸었다.
 * REJECTED 로 남은 쌍도 행이 유지되므로 "중복 아님" 판정이 재실행에도 유지된다.
 */
@Getter
@Entity
@Table(
	name = "scholarship_merge_candidate",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_merge_candidate_pair",
		columnNames = {"primary_scholarship_id", "duplicate_scholarship_id"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScholarshipMergeCandidate extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 남길 장학금. */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "primary_scholarship_id", nullable = false)
	private Scholarship primary;

	/** 병합 후 소프트 삭제될 장학금. */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "duplicate_scholarship_id", nullable = false)
	private Scholarship duplicate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MergeCandidateStatus status;

	/** LLM 이 중복이라고 본 이유. 사람이 승인 여부를 판단하는 근거가 된다. */
	@Column(columnDefinition = "TEXT")
	private String reason;

	/** 병합 결과·실패 사유 등 처리 기록. */
	@Column(columnDefinition = "TEXT")
	private String note;

	/** 승인·반려한 관리자. */
	@Column(name = "reviewed_by")
	private UUID reviewedBy;

	@Column(name = "reviewed_at")
	private LocalDateTime reviewedAt;

	@Builder
	private ScholarshipMergeCandidate(Scholarship primary, Scholarship duplicate, String reason) {
		this.primary = primary;
		this.duplicate = duplicate;
		this.reason = reason;
		this.status = MergeCandidateStatus.PENDING;
	}

	public void markMerged(UUID reviewer, String note) {
		this.status = MergeCandidateStatus.MERGED;
		this.note = note;
		review(reviewer);
	}

	public void markRejected(UUID reviewer, String note) {
		this.status = MergeCandidateStatus.REJECTED;
		this.note = note;
		review(reviewer);
	}

	public void markFailed(UUID reviewer, String note) {
		this.status = MergeCandidateStatus.FAILED;
		this.note = note;
		review(reviewer);
	}

	private void review(UUID reviewer) {
		this.reviewedBy = reviewer;
		this.reviewedAt = LocalDateTime.now();
	}

	public boolean isPending() {
		return status == MergeCandidateStatus.PENDING;
	}
}
