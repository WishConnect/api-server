package com.wishconnect.domain.scholarship.controller;

import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse;
import com.wishconnect.domain.scholarship.dto.HomeSummaryResponse;
import com.wishconnect.domain.scholarship.service.ScholarshipRecommendationService;
import com.wishconnect.global.common.ApiResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
장학금 조회/추천 API 컨트롤러입니다. (동기화는 ScholarshipSyncController 담당)
 */
@RestController
@RequestMapping("/api/v1/scholarships")
@RequiredArgsConstructor
public class ScholarshipController {

	private final ScholarshipRecommendationService scholarshipRecommendationService;

	/** 맞춤 추천(큐레이팅) 목록. 프로필 기준 지원 가능 공고를 점수순으로 반환. */
	@GetMapping("/curated")
	public ApiResponse<List<CuratedScholarshipResponse>> getCurated(@AuthenticationPrincipal String userId) {
		return ApiResponse.ok(scholarshipRecommendationService.getCuratedScholarships(UUID.fromString(userId)));
	}

	/** 홈 화면 요약(맞춤 건수 / 마감 임박 건수). */
	@GetMapping("/home-summary")
	public ApiResponse<HomeSummaryResponse> getHomeSummary(@AuthenticationPrincipal String userId) {
		return ApiResponse.ok(scholarshipRecommendationService.getHomeSummary(UUID.fromString(userId)));
	}
}
