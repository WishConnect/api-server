package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 맞춤 추천 목록(메인) 응답. 노션 명세(GET /api/v1/scholarships/curated) 구조.
 *
 * <ul>
 *   <li>{@code featured} — 마감임박 히어로 배너. 피그마가 dot 캐러셀이라 <b>배열</b>이다(최대 5개).</li>
 *   <li>{@code campusScholarships} — 교내(INTERNAL) 중 <b>사용자 소속 학교</b> 것만.</li>
 *   <li>{@code otherScholarships} — 지원 가능한 그 외 추천. 페이지네이션 대상.</li>
 *   <li>{@code ineligibleScholarships} — 조건 미충족. 피그마상 별도 섹션이라 분리했다(전체 반환).</li>
 * </ul>
 */
public record CuratedScholarshipResponse(
		List<ScholarshipCard> featured,
		int profileCompletionRate,
		List<ScholarshipCard> campusScholarships,
		List<ScholarshipCard> otherScholarships,
		List<ScholarshipCard> ineligibleScholarships,
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
			boolean eligible,
			boolean isScrapped
	) {

		public static ScholarshipCard of(Scholarship scholarship, int matchScore, List<String> matchReasons,
				boolean eligible, boolean isScrapped) {
			return new ScholarshipCard(
					scholarship.getId(),
					scholarship.getTitle(),
					scholarship.getProvider(),
					formatAmount(scholarship.getAmount()),
					toDate(scholarship.getApplicationEndAt()),
					calculateDday(scholarship.getApplicationEndAt()),
					matchScore,
					matchReasons,
					eligible,
					isScrapped
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
