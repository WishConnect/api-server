package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 맞춤 추천 목록 항목. dDay 는 모집 마감까지 남은 일수(마감일 없으면 null).
 */
public record CuratedScholarshipResponse(
		Long scholarshipId,
		String title,
		String provider,
		Long amount,
		LocalDateTime applicationEndAt,
		Long dDay,
		int matchScore,
		String matchReason
) {

	public static CuratedScholarshipResponse of(Scholarship scholarship, int matchScore, String matchReason) {
		return new CuratedScholarshipResponse(
				scholarship.getId(),
				scholarship.getTitle(),
				scholarship.getProvider(),
				scholarship.getAmount(),
				scholarship.getApplicationEndAt(),
				calculateDday(scholarship.getApplicationEndAt()),
				matchScore,
				matchReason
		);
	}

	private static Long calculateDday(LocalDateTime applicationEndAt) {
		if (applicationEndAt == null) {
			return null;
		}
		return ChronoUnit.DAYS.between(LocalDate.now(), applicationEndAt.toLocalDate());
	}
}
