package com.wishconnect.domain.common.controller;

import com.wishconnect.domain.common.dto.UniversityResponse;
import com.wishconnect.domain.common.service.UniversitySearchService;
import com.wishconnect.global.common.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/*
온보딩 학교 입력 자동완성 API입니다.
학교는 user 전용 값이 아니라 장학금 매칭에서도 쓰일 수 있어 common 도메인에서 조회합니다.
 */
@RestController
@RequestMapping("/api/v1/universities")
@RequiredArgsConstructor
public class UniversityController {

	private final UniversitySearchService universitySearchService;

	@GetMapping("/search")
	public ApiResponse<List<UniversityResponse>> search(@RequestParam String keyword) {
		return ApiResponse.ok(universitySearchService.search(keyword));
	}
}
