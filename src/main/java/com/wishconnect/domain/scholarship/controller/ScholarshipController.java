package com.wishconnect.domain.scholarship.controller;

import com.wishconnect.domain.scholarship.collector.UnivNoticeCollector;
import com.wishconnect.domain.scholarship.dto.CollectResultResponse;
import com.wishconnect.domain.scholarship.dto.ConditionExtractionResponse;
import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse;
import com.wishconnect.domain.scholarship.dto.HomeSummaryResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipDetailResponse;
import com.wishconnect.domain.scholarship.service.ConditionExtractionService;
import com.wishconnect.domain.scholarship.service.ScholarshipDetailService;
import com.wishconnect.domain.scholarship.service.ScholarshipRecommendationService;
import com.wishconnect.global.common.ApiResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/*
장학금 큐레이팅(추천/매칭)·상세 API 컨트롤러입니다. (동기화는 ScholarshipSyncController 담당)
 */
@RestController
@RequestMapping("/api/v1/scholarships")
@RequiredArgsConstructor
public class ScholarshipController {

	private final ScholarshipRecommendationService scholarshipRecommendationService;
	private final ScholarshipDetailService scholarshipDetailService;
	private final ConditionExtractionService conditionExtractionService;
	private final UnivNoticeCollector univNoticeCollector;

	/**
	 * 맞춤 추천 목록(메인). featured/교내/그 외(+조건 미충족 분류)와 페이지네이션 포함.
	 * category 필터는 태그 데이터 확보 전까지 미적용(파라미터만 수용).
	 */
	@GetMapping("/curated")
	public ApiResponse<CuratedScholarshipResponse> getCurated(
			@AuthenticationPrincipal String userId,
			@RequestParam(required = false) String category,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "10") int size) {
		return ApiResponse.ok(
				scholarshipRecommendationService.getCuratedScholarships(UUID.fromString(userId), page, size));
	}

	/** 홈 - 오늘의 장학금 소식 요약(신규 맞춤/이번 주 마감 건수). */
	@GetMapping("/home-summary")
	public ApiResponse<HomeSummaryResponse> getHomeSummary(@AuthenticationPrincipal String userId) {
		return ApiResponse.ok(scholarshipRecommendationService.getHomeSummary(UUID.fromString(userId)));
	}

	/** 장학금 상세(요약 테이블 + 선발 일정 타임라인 + 제출 서류 + 매칭 사유). */
	@GetMapping("/{scholarshipId}")
	public ApiResponse<ScholarshipDetailResponse> getDetail(
			@AuthenticationPrincipal String userId,
			@PathVariable Long scholarshipId) {
		return ApiResponse.ok(scholarshipDetailService.getDetail(UUID.fromString(userId), scholarshipId));
	}

	/** 대학 장학공지 크롤링 수집(운영/개발용 수동 트리거). code=yml의 사이트 코드. */
	@PostMapping("/collect/univ/{code}")
	public ApiResponse<CollectResultResponse> collectUniv(
			@PathVariable String code,
			@RequestParam(defaultValue = "1") int pages) {
		return ApiResponse.ok(univNoticeCollector.collectByCode(code, pages)
				.orElseThrow(() -> new com.wishconnect.global.exception.CustomException(
						com.wishconnect.global.exception.ErrorCode.INVALID_INPUT)));
	}

	/** LLM 조건 구조화 추출 실행(운영/개발용 수동 트리거, sync 후 실행 권장). */
	@PostMapping("/conditions/extract")
	public ApiResponse<ConditionExtractionResponse> extractConditions() {
		return ApiResponse.ok(conditionExtractionService.extract());
	}
}
