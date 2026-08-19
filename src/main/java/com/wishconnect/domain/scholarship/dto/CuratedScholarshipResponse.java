package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 큐레이팅(메인) 응답. GET /api/v1/scholarships/curated.
 *
 * <ul>
 *   <li>{@code viewMode} — 화면 상태. 어떤 섹션이 채워지는지가 이 값에 달려 있다.</li>
 *   <li>{@code featured} — 지원 가능한 전체 추천. 프론트가 처음 5개와 더보기를 나눠 그린다.</li>
 *   <li>{@code campusScholarships} — 교내(INTERNAL) 중 <b>사용자 소속 학교</b> 것만.</li>
 *   <li>{@code ineligibleScholarships} — 조건 미충족. 피그마상 별도 섹션이라 분리했다(전체 반환).</li>
 *   <li>{@code otherScholarships} — featured 상위 5개를 제외한 지원 가능 목록. 페이지네이션 대상.</li>
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
@Schema(description = "인증·온보딩 상태에 따라 다른 섹션을 채우는 장학금 큐레이팅 응답")
public record CuratedScholarshipResponse(
		CuratedViewMode viewMode,
		String rankerVersion,
		List<ScholarshipCard> featured,
		int profileCompletionRate,
		List<ScholarshipCard> campusScholarships,
		List<ScholarshipCard> ineligibleScholarships,
		List<ScholarshipCard> otherScholarships,
		Pagination pagination
		@Schema(description = "프론트가 그려야 할 큐레이팅 화면 상태", example = "PERSONALIZED") CuratedViewMode viewMode,
		@Schema(description = "현재 추천 점수식 버전. 이벤트 요청에 그대로 전달", example = "v2") String rankerVersion,
		@Schema(description = "히어로 캐러셀. GUEST는 빈 배열, 나머지는 최대 5건") List<ScholarshipCard> featured,
		@Schema(description = "프로필 완성도(0~100)", example = "80") int profileCompletionRate,
		@Schema(description = "PERSONALIZED에서만 채워지는 사용자 소속 학교의 교내 장학금") List<ScholarshipCard> campusScholarships,
		@Schema(description = "GUEST의 일반 목록 또는 PERSONALIZED의 지원 가능한 교외 추천 목록") List<ScholarshipCard> otherScholarships,
		@Schema(description = "PERSONALIZED에서 필수 조건을 충족하지 못한 장학금") List<ScholarshipCard> ineligibleScholarships,
		@Schema(description = "otherScholarships에 대한 1 기반 페이징 정보") Pagination pagination
) {

	@Schema(description = "큐레이팅 섹션에 노출할 장학금 카드")
	public record ScholarshipCard(
			@Schema(description = "카드 섹션. 이벤트 요청에 그대로 전달", allowableValues = {"featured", "campus", "other", "ineligible"}, example = "other") String section,
			@Schema(description = "장학금 ID", example = "1024") Long scholarshipId,
			@Schema(description = "장학금명", example = "2026 미래인재 성장 장학금") String title,
			@Schema(description = "운영 기관", example = "한국장학재단") String organization,
			/** 포스터 이미지. 카드 그리드가 이미지 중심이라 목록에서도 내려준다. 없으면 null. */
			@Schema(description = "포스터 공개 URL. 없으면 null") String posterUrl,
			@Schema(description = "화면 표시용 최대 지원금", example = "최대 200만원") String maxAmount,
			@Schema(description = "마감 날짜. 상시모집이면 null", example = "2026-08-31") LocalDate deadline,
			/**
			 * 마감 일시. 카드가 "2026.06.30 (화) 15:00" 처럼 시각까지 보여주는데
			 * {@link #deadline} 은 날짜뿐이라 시각을 복원할 수 없다.
			 */
			@Schema(description = "마감 일시. 시간까지 표시할 때 사용", example = "2026-08-31T18:00:00") LocalDateTime deadlineAt,
			@Schema(description = "오늘 기준 마감까지 남은 날. 마감일이 없으면 null", example = "12") Long dDay,
			@Schema(description = "노출 당시 추천 점수(0~100)", example = "85") int matchScore,
			@Schema(description = "프로필과 조건을 대조한 추천 이유") List<String> matchReasons,
			@Schema(description = "필수 조건 기준 지원 가능 여부") boolean eligible,
			@Schema(description = "현재 사용자의 스크랩 여부. 비로그인은 false") boolean isScrapped
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

	@Schema(description = "1부터 시작하는 큐레이팅 페이징 정보")
	public record Pagination(
			@Schema(example = "1") int page,
			@Schema(example = "10") int size,
			@Schema(example = "42") long totalCount,
			@Schema(example = "5") int totalPages) {
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
