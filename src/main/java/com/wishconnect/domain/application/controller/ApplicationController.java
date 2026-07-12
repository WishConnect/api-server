package com.wishconnect.domain.application.controller;

import com.wishconnect.domain.application.dto.request.CreateApplicationRequest;
import com.wishconnect.domain.application.dto.response.ApplicationListResponse;
import com.wishconnect.domain.application.dto.response.CreateApplicationResponse;
import com.wishconnect.domain.application.entity.EssayStatus;
import com.wishconnect.domain.application.service.EssayApplicationService;
import com.wishconnect.global.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자기소개서(지원서) API 컨트롤러. Notion API 명세서의 ①·②·③ 엔드포인트를 담당한다.
 */
@RestController
@RequestMapping("/api/v1/applications")
@RequiredArgsConstructor
public class ApplicationController {

	private final EssayApplicationService essayApplicationService;

	/**
	 * ① 지원서 목록 조회.
	 *
	 * @param status   optional. NOT_STARTED / IN_PROGRESS / COMPLETED
	 * @param pageable page/size/sort (기본 정렬: updatedAt desc)
	 */
	@GetMapping
	public ApiResponse<ApplicationListResponse> getApplications(
			@AuthenticationPrincipal String userId,
			@RequestParam(required = false) EssayStatus status,
			@PageableDefault(sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return ApiResponse.ok(
				essayApplicationService.getApplications(UUID.fromString(userId), status, pageable));
	}

	/**
	 * ② 지원서 작성 시작. essay + essay_question + 빈 essay_answer 를 생성한다.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<CreateApplicationResponse> createApplication(
			@AuthenticationPrincipal String userId,
			@Valid @RequestBody CreateApplicationRequest request) {
		return ApiResponse.ok(
				essayApplicationService.createApplication(UUID.fromString(userId), request.scholarshipId()));
	}
}
