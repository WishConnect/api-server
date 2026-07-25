package com.wishconnect.domain.common.controller;

import com.wishconnect.domain.common.dto.AcademicInfoSyncResponse;
import com.wishconnect.domain.common.dto.UniversityResponse;
import com.wishconnect.domain.common.service.AcademicInfoSyncService;
import com.wishconnect.domain.common.service.UniversitySearchService;
import com.wishconnect.global.common.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/*
온보딩 학교 입력 자동완성 API입니다.
학교는 user 전용 값이 아니라 장학금 매칭에서도 쓰일 수 있어 common 도메인에서 조회합니다.
 */
@Tag(name = "공통 - 학교", description = "학교 검색 및 학사정보 동기화")
@RestController
@RequestMapping("/api/v1/universities")
@RequiredArgsConstructor
public class UniversityController {

	private final UniversitySearchService universitySearchService;
	private final AcademicInfoSyncService academicInfoSyncService;

	/** 학교명을 키워드로 검색한다(온보딩 학교 선택용). */
	@GetMapping("/search")
	public ApiResponse<List<UniversityResponse>> search(@RequestParam String keyword) {
		return ApiResponse.ok(universitySearchService.search(keyword));
	}

	/** 학교·전공 마스터 데이터를 외부 학사정보에서 동기화한다(운영/개발용 수동 트리거). */
	@PostMapping("/sync")
	public ApiResponse<AcademicInfoSyncResponse> syncAcademicInfo() {
		return ApiResponse.ok(academicInfoSyncService.sync());
	}

}
