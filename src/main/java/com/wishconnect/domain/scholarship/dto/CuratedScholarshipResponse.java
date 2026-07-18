package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 맞춤 추천 목록(메인) 응답. 노션 명세(GET /api/v1/scholarships/curated) 구조.
 * featured = 마감임박 대표 카드, campusScholarships = 교내(INTERNAL),
 * otherScholarships = 그 외 추천(조건 미충족 분류 포함, eligible=false).
 */
public record CuratedScholarshipResponse(
		ScholarshipCard featured,
		int profileCompletionRate,
		List<ScholarshipCard> campusScholarships,
		List<ScholarshipCard> otherScholarships,
		Pagination pagination
) {

	public record ScholarshipCard(
			Long scholarshipId,
			String title,
			String organization,
			String maxAmount,
			LocalDate deadline,
			Long dDay,
			int matchScore,
			List<String> matchReasons,
			boolean eligible
	) {

		public static ScholarshipCard of(Scholarship scholarship, int matchScore, List<String> matchReasons,
				boolean eligible) {
			return new ScholarshipCard(
					scholarship.getId(),
					scholarship.getTitle(),
					scholarship.getProvider(),
					formatAmount(scholarship.getAmount()),
					toDate(scholarship.getApplicationEndAt()),
					calculateDday(scholarship.getApplicationEndAt()),
					matchScore,
					matchReasons,
					eligible
			);
		}
	}

	public record Pagination(int page, int size, long totalCount, int totalPages) {
	}

	public static String formatAmount(Long amount) {
		if (amount == null || amount <= 0) {
			return null;
		}
		return "최대 " + (amount / 10_000) + "만원";
	}

	static LocalDate toDate(LocalDateTime dateTime) {
		return dateTime == null ? null : dateTime.toLocalDate();
	}

	public static Long calculateDday(LocalDateTime applicationEndAt) {
		if (applicationEndAt == null) {
			return null;
		}
		return ChronoUnit.DAYS.between(LocalDate.now(), applicationEndAt.toLocalDate());
	}
}
