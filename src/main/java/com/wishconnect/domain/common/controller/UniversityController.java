package com.wishconnect.domain.common.controller;

import com.wishconnect.domain.common.dto.AcademicInfoSyncStatusResponse;
import com.wishconnect.domain.common.dto.UniversityResponse;
import com.wishconnect.domain.common.service.AcademicInfoSyncService;
import com.wishconnect.domain.common.service.UniversitySearchService;
import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
	@Operation(summary = "학교 검색", description = "온보딩·프로필에서 사용할 대학명을 키워드로 검색합니다.")
	public ApiResponse<List<UniversityResponse>> search(@RequestParam String keyword) {
		return ApiResponse.ok(universitySearchService.search(keyword));
	}

	/**
	 * 학교·전공 마스터 데이터를 외부 학사정보에서 동기화한다(운영용 수동 트리거).
	 * 외부 공공데이터 API 를 대량 호출하므로 ADMIN 만 실행할 수 있다.
	 *
	 * <p>전체 동기화는 수 분이 걸려 nginx 60초 타임아웃을 넘긴다. 따라서 실행만 걸고
	 * 202 Accepted 로 즉시 응답하며, 완료 여부는 {@code GET /sync/status} 로 확인한다.
	 */
	@Operation(summary = "학교·전공 동기화 시작(ADMIN)",
			description = "백그라운드로 실행하고 즉시 202 를 반환한다. 이미 실행 중이면 진행 중인 작업 상태를 그대로 돌려준다.")
	@PreAuthorize("hasRole('ADMIN')")
	@PostMapping("/sync")
	public ResponseEntity<ApiResponse<AcademicInfoSyncStatusResponse>> syncAcademicInfo() {
		return ResponseEntity.accepted().body(ApiResponse.ok(academicInfoSyncService.start()));
	}

	/** 마지막(또는 진행 중) 동기화 상태를 조회한다. */
	@Operation(summary = "학교·전공 동기화 상태 조회(ADMIN)",
			description = "현재 진행 중이거나 가장 최근에 완료된 학교·전공 마스터 동기화 상태와 처리 건수를 반환합니다.")
	@PreAuthorize("hasRole('ADMIN')")
	@GetMapping("/sync/status")
	public ApiResponse<AcademicInfoSyncStatusResponse> syncStatus() {
		return ApiResponse.ok(academicInfoSyncService.status());
	}

}
