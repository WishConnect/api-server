package com.wishconnect.domain.archive.controller;

import com.wishconnect.domain.archive.dto.ArchiveResponse;
import com.wishconnect.domain.archive.dto.ScrapResponse;
import com.wishconnect.domain.archive.service.ArchiveService;
import com.wishconnect.domain.archive.service.ScrapService;
import com.wishconnect.global.common.ApiResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/*
아카이빙(스크랩) API 컨트롤러입니다. 노션 명세: POST /api/v1/archive/{scholarshipId}/scrap
 */
@Tag(name = "아카이빙", description = "장학금 스크랩 추가·해제")
@RestController
@RequestMapping("/api/v1/archive")
@RequiredArgsConstructor
public class ArchiveController {

	private final ScrapService scrapService;
	private final ArchiveService archiveService;

	@GetMapping
	public ApiResponse<ArchiveResponse> getArchive(
			@AuthenticationPrincipal String userIdStr,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String keyword,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "10") int size
	) {
		UUID userId = UUID.fromString(userIdStr);
		ArchiveResponse response = archiveService.getArchive(userId, status, keyword, page, size);
		return ApiResponse.ok(response);
	}

	/** 장학금 스크랩 등록 (201, 중복 409, 없는 장학금 404). */
	@PostMapping("/{scholarshipId}/scrap")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<ScrapResponse> scrap(
			@AuthenticationPrincipal String userId,
			@PathVariable Long scholarshipId) {
		scrapService.scrap(UUID.fromString(userId), scholarshipId);
		return ApiResponse.ok(new ScrapResponse(true));
	}

	/** 장학금 스크랩 해제 (미스크랩 404). */
	@DeleteMapping("/{scholarshipId}/scrap")
	public ApiResponse<ScrapResponse> unscrap(
			@AuthenticationPrincipal String userId,
			@PathVariable Long scholarshipId) {
		scrapService.unscrap(UUID.fromString(userId), scholarshipId);
		return ApiResponse.ok(new ScrapResponse(false));
	}
}
