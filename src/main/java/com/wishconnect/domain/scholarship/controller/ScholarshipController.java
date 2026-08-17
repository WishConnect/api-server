package com.wishconnect.domain.scholarship.controller;

import com.wishconnect.domain.scholarship.dto.*;
import com.wishconnect.domain.scholarship.service.ScholarshipCalendarService;
import com.wishconnect.domain.scholarship.service.ScholarshipDetailService;
import com.wishconnect.domain.scholarship.service.ScholarshipRecommendationService;
import com.wishconnect.domain.scholarship.service.ScholarshipService;
import com.wishconnect.global.common.ApiResponse;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/*
장학금 큐레이팅(추천/매칭)·상세 API 컨트롤러입니다. (동기화는 ScholarshipSyncController 담당)
 */
@Tag(name = "장학금", description = "큐레이팅·상세·검색·홈 요약")
@RestController
@RequestMapping("/api/v1/scholarships")
@RequiredArgsConstructor
public class ScholarshipController {

	private final ScholarshipRecommendationService scholarshipRecommendationService;
	private final ScholarshipDetailService scholarshipDetailService;
	private final ScholarshipCalendarService scholarshipCalendarService;
	private final ScholarshipService scholarshipService;
	
	/**
	 * 큐레이팅 메인. 응답의 {@code viewMode} 가 세 화면 중 어느 것인지 알려준다.
	 *
	 * <p>비로그인도 볼 수 있다. 가입 전에 "여기 뭐가 있는지" 를 보여주지 못하면 가입할 이유가
	 * 생기지 않는다. 이때는 추천 없이 {@code sort} 기준으로만 정렬한다.
	 *
	 * <p>{@code sort} 는 비로그인 화면의 드롭다운(최신 등록순/마감 임박순)이다. 로그인 상태에는
	 * 화면에 드롭다운이 없어 무시된다. category 필터는 태그 데이터 확보 전까지 미적용(파라미터만 수용).
	 */
	@GetMapping("/curated")
	public ApiResponse<CuratedScholarshipResponse> getCurated(
			@AuthenticationPrincipal String userIdStr,
			@RequestParam(required = false) String category,
			@RequestParam(defaultValue = "DEADLINE") CuratedSort sort,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "10") int size) {
		return ApiResponse.ok(scholarshipRecommendationService.getCuratedScholarships(
				resolveUserId(userIdStr), sort, page, size));
	}

	/** 홈 - 오늘의 장학금 소식 요약(신규 맞춤/이번 주 마감 건수). */
	@GetMapping("/home-summary")
	public ApiResponse<HomeSummaryResponse> getHomeSummary(@AuthenticationPrincipal String userId) {
		return ApiResponse.ok(scholarshipRecommendationService.getHomeSummary(UUID.fromString(userId)));
	}

	/**
	 * 홈 - 이번 달 일정 달력. 모집 시작·마감을 각각 이벤트로 준다.
	 *
	 * <p>year·month 를 생략하면 이번 달. scope 는 MATCHED(기본, 지원 가능) / SCRAPPED / ALL.
	 * 전체를 다 띄우면 달력이 빽빽해 쓸모없어서 기본은 지원 가능한 것만 준다.
	 */
	@GetMapping("/calendar")
	public ApiResponse<ScholarshipCalendarResponse> getCalendar(
			@AuthenticationPrincipal String userId,
			@RequestParam(required = false) Integer year,
			@RequestParam(required = false) Integer month,
			@RequestParam(required = false) CalendarScope scope) {
		return ApiResponse.ok(
				scholarshipCalendarService.getCalendar(UUID.fromString(userId), year, month, scope));
	}

	/** 장학금 상세(요약 테이블 + 선발 일정 타임라인 + 제출 서류 + 매칭 사유). */
	@GetMapping("/{scholarshipId}")
	public ApiResponse<ScholarshipDetailResponse> getDetail(
			@AuthenticationPrincipal String userId,
			@PathVariable Long scholarshipId) {
		return ApiResponse.ok(scholarshipDetailService.getDetail(UUID.fromString(userId), scholarshipId));
	}

	/** 장학금을 키워드·카테고리로 검색한다. 비로그인도 조회 가능하며 이때 isScrapped 는 항상 false 다. */
	@GetMapping("/search")
	public ApiResponse<ScholarshipSearchResponse> searchScholarships(
			@AuthenticationPrincipal String userIdStr,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String category,
			@RequestParam(defaultValue = "deadline") String sort,
			@RequestParam(defaultValue = "false") boolean scrappedOnly,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "10") int size
	) {
		UUID userId = resolveUserId(userIdStr);
		return ApiResponse.ok(scholarshipService.search(userId, keyword, category, sort, scrappedOnly, page, size));
	}


	private UUID resolveUserId(String userIdStr) {
		if (userIdStr == null || "anonymousUser".equals(userIdStr)) {
			return null;
		}
		return UUID.fromString(userIdStr);
	}
}
