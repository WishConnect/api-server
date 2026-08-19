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
import io.swagger.v3.oas.annotations.Operation;

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
	@Operation(summary = "내 스크랩 장학금 조회", description = "로그인 사용자의 스크랩을 상태·키워드로 필터링하고 1부터 시작하는 페이지로 조회합니다.")
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
	@Operation(summary = "장학금 스크랩", description = "장학금을 내 아카이브에 추가합니다. 이미 스크랩했으면 409를 반환합니다.")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<ScrapResponse> scrap(
			@AuthenticationPrincipal String userId,
			@PathVariable Long scholarshipId) {
		scrapService.scrap(UUID.fromString(userId), scholarshipId);
		return ApiResponse.ok(new ScrapResponse(true));
	}

	/** 장학금 스크랩 해제 (미스크랩 404). */
	@DeleteMapping("/{scholarshipId}/scrap")
	@Operation(summary = "장학금 스크랩 해제", description = "장학금을 내 아카이브에서 제거합니다. 스크랩하지 않은 장학금이면 404를 반환합니다.")
	public ApiResponse<ScrapResponse> unscrap(
			@AuthenticationPrincipal String userId,
			@PathVariable Long scholarshipId) {
		scrapService.unscrap(UUID.fromString(userId), scholarshipId);
		return ApiResponse.ok(new ScrapResponse(false));
	}
}
