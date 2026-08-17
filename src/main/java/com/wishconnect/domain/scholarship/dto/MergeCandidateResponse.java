package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.MergeCandidateStatus;
import com.wishconnect.domain.scholarship.entity.ScholarshipMergeCandidate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 병합 후보 조회 응답. 어드민이 승인 여부를 판단할 재료를 담는다.
 *
 * <p>두 장학금의 제목·기관·기간·출처를 나란히 보여준다. 캠퍼스만 다른 별개 모집이
 * 후보로 올라오는 경우가 있어(실측: 복지장학금 서울/다빈치), 사람이 직접 비교해야 한다.
 */
public record MergeCandidateResponse(
		int totalCount,
		List<Item> items
) {

	public record Item(
			Long candidateId,
			MergeCandidateStatus status,
			String reason,
			String note,
			LocalDateTime reviewedAt,
			Side primary,
			Side duplicate
	) {
	}

	/** 비교 대상 한 쪽의 요약. */
	public record Side(
			Long scholarshipId,
			String title,
			String provider,
			String scholarshipType,
			String applicationPeriod,
			Long amount,
			Integer selectionCount,
			String source,
			String homepageUrl,
			boolean verified
	) {

		static Side of(com.wishconnect.domain.scholarship.entity.Scholarship s) {
			return new Side(
					s.getId(),
					s.getTitle(),
					s.getProvider(),
					s.getScholarshipType() == null ? null : s.getScholarshipType().name(),
					describePeriod(s),
					s.getAmount(),
					s.getSelectionCount(),
					s.getPrimarySource(),
					s.getHomepageUrl(),
					s.isVerified());
		}

		private static String describePeriod(
				com.wishconnect.domain.scholarship.entity.Scholarship s) {
			if (s.getApplicationStartAt() == null && s.getApplicationEndAt() == null) {
				return null;
			}
			return format(s.getApplicationStartAt()) + " ~ " + format(s.getApplicationEndAt());
		}

		private static String format(LocalDateTime value) {
			return value == null ? "" : value.toLocalDate().toString();
		}
	}

	public static MergeCandidateResponse of(List<ScholarshipMergeCandidate> candidates, int total) {
		return new MergeCandidateResponse(total, candidates.stream()
				.map(c -> new Item(
						c.getId(), c.getStatus(), c.getReason(), c.getNote(), c.getReviewedAt(),
						Side.of(c.getPrimary()), Side.of(c.getDuplicate())))
				.toList());
	}
}
