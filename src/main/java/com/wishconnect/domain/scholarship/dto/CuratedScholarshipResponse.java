package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 큐레이팅(메인) 응답. GET /api/v1/scholarships/curated.
 *
 * <ul>
 *   <li>{@code viewMode} — 화면 상태. 어떤 섹션이 채워지는지가 이 값에 달려 있다.</li>
 *   <li>{@code featured} — 마감임박 히어로 배너. 피그마가 dot 캐러셀이라 <b>배열</b>이다(최대 5개).</li>
 *   <li>{@code campusScholarships} — 교내(INTERNAL) 중 <b>사용자 소속 학교</b> 것만.</li>
 *   <li>{@code otherScholarships} — 카드 그리드. 페이지네이션 대상.</li>
 *   <li>{@code ineligibleScholarships} — 조건 미충족. 피그마상 별도 섹션이라 분리했다(전체 반환).</li>
 * </ul>
 *
 * <p>상태별로 채워지는 섹션:
 *
 * <table border="1">
 *   <tr><th></th><th>GUEST</th><th>ONBOARDING_REQUIRED</th><th>PERSONALIZED</th></tr>
 *   <tr><td>featured</td><td>-</td><td>마감임박 5</td><td>마감임박 5</td></tr>
 *   <tr><td>campus</td><td>-</td><td>잠김</td><td>소속 학교</td></tr>
 *   <tr><td>other</td><td>정렬순 전체</td><td>잠김</td><td>점수순</td></tr>
 *   <tr><td>ineligible</td><td>-</td><td>잠김</td><td>조건 미충족</td></tr>
 * </table>
 *
 * <p>GUEST·ONBOARDING_REQUIRED 에서는 판정 근거가 없어 {@code matchScore} 가 0,
 * {@code matchReasons} 가 빈 배열이다. 없는 근거를 지어내지 않는다.
 */
public record CuratedScholarshipResponse(
		CuratedViewMode viewMode,
		String rankerVersion,
		List<ScholarshipCard> featured,
		int profileCompletionRate,
		List<ScholarshipCard> campusScholarships,
		List<ScholarshipCard> otherScholarships,
		List<ScholarshipCard> ineligibleScholarships,
		Pagination pagination
) {

	public record ScholarshipCard(
			String section,
			Long scholarshipId,
			String title,
			String organization,
			/** 포스터 이미지. 카드 그리드가 이미지 중심이라 목록에서도 내려준다. 없으면 null. */
			String posterUrl,
			String maxAmount,
			LocalDate deadline,
			/**
			 * 마감 일시. 카드가 "2026.06.30 (화) 15:00" 처럼 시각까지 보여주는데
			 * {@link #deadline} 은 날짜뿐이라 시각을 복원할 수 없다.
			 */
			LocalDateTime deadlineAt,
			Long dDay,
			int matchScore,
			List<String> matchReasons,
			boolean eligible,
			boolean isScrapped
	) {

		public static ScholarshipCard of(String section, Scholarship scholarship, String posterUrl, int matchScore,
				List<String> matchReasons, boolean eligible, boolean isScrapped) {
			return new ScholarshipCard(
					section,
					scholarship.getId(),
					scholarship.getTitle(),
					scholarship.getProvider(),
					posterUrl,
					formatAmount(scholarship.getAmount()),
					toDate(scholarship.getApplicationEndAt()),
					scholarship.getApplicationEndAt(),
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
