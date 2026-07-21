package com.wishconnect.domain.scholarship.controller;

import com.wishconnect.domain.scholarship.dto.ScholarshipSearchResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipSyncResponse;
import com.wishconnect.domain.scholarship.service.ScholarshipService;
import com.wishconnect.domain.scholarship.service.ScholarshipSyncService;
import com.wishconnect.global.common.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/*
장학금 외부 API 동기화를 수동으로 실행하는 컨트롤러입니다.
현재는 개발/관리용으로 raw_scholarship 수집 결과를 확인하는 진입점 역할을 합니다.
(나중에는 자동 동기화로 가져오게 할 것입니다.)
 */
@RestController
@RequestMapping("/api/v1/scholarships")
@Profile("!test")
public class ScholarshipSyncController {

	private final ScholarshipSyncService scholarshipSyncService;
	private final ScholarshipService scholarshipService;

	public ScholarshipSyncController(ScholarshipSyncService scholarshipSyncService, ScholarshipService scholarshipService) {
		this.scholarshipSyncService = scholarshipSyncService;
		this.scholarshipService = scholarshipService;
	}

	@PostMapping("/sync")
	public ApiResponse<ScholarshipSyncResponse> syncScholarships() {
		return ApiResponse.ok(scholarshipSyncService.sync());
	}

	@GetMapping("/search")
	public ResponseEntity<ApiResponse<ScholarshipSearchResponse>> searchScholarships(
			@AuthenticationPrincipal String userIdStr,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String category,
			@RequestParam(defaultValue = "deadline") String sort,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "10") int size
	) {
		UUID userId = resolveUserId(userIdStr);

		ScholarshipSearchResponse response = scholarshipService.search(userId, keyword, category, sort, page, size);

		return ResponseEntity.ok(ApiResponse.ok(response));

	}

	private UUID resolveUserId(String userIdStr) {
		if (userIdStr == null || "anonymousUser".equals(userIdStr)) {
			return null;
		}
		return UUID.fromString(userIdStr);
	}
}
