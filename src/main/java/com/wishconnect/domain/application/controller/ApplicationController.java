package com.wishconnect.domain.application.controller;

import com.wishconnect.domain.application.dto.request.AnswerActionRequest;
import com.wishconnect.domain.application.dto.request.CreateApplicationRequest;
import com.wishconnect.domain.application.dto.request.InterviewAnswerRequest;
import com.wishconnect.domain.application.dto.response.AnswerActionResponse;
import com.wishconnect.domain.application.dto.response.ApplicationDetailResponse;
import com.wishconnect.domain.application.dto.response.ApplicationListResponse;
import com.wishconnect.domain.application.dto.response.CreateApplicationResponse;
import com.wishconnect.domain.application.dto.response.InterviewAdvanceResponse;
import com.wishconnect.domain.application.entity.EssayStatus;
import com.wishconnect.domain.application.service.AnswerService;
import com.wishconnect.domain.application.service.EssayApplicationService;
import com.wishconnect.domain.application.service.InterviewService;
import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자기소개서(지원서) API 컨트롤러. Notion API 명세서의 ①·②·③·④·⑤ 엔드포인트를 담당한다.
 */
@Tag(name = "자기소개서", description = "지원서 생성·조회, STEP1 AI 인터뷰, STEP2 초안·저장·완료 관리")
@RestController
@RequestMapping("/api/v1/applications")
@RequiredArgsConstructor
public class ApplicationController {

	private final EssayApplicationService essayApplicationService;
	private final InterviewService interviewService;
	private final AnswerService answerService;

	/**
	 * ① 지원서 목록 조회.
	 *
	 * @param status   optional. NOT_STARTED / IN_PROGRESS / COMPLETED
	 * @param pageable page/size/sort (기본 정렬: updatedAt desc)
	 */
	@Operation(summary = "① 지원서 목록 조회",
			description = "사용자의 지원서 목록을 상태별로 필터링해 페이지네이션 반환. 아카이빙 화면에서 사용.")
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
	@Operation(summary = "② 지원서 작성 시작",
			description = "장학금 ID 를 받아 essay + essay_question + 빈 essay_answer 를 한 트랜잭션에서 일괄 생성.")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<CreateApplicationResponse> createApplication(
			@AuthenticationPrincipal String userId,
			@Valid @RequestBody CreateApplicationRequest request) {
		return ApiResponse.ok(
				essayApplicationService.createApplication(UUID.fromString(userId), request.scholarshipId()));
	}

	/**
	 * ③ 지원서 통합 상세 조회. 지원서 화면 진입 시 필요한 모든 데이터를 1회 호출로 반환한다.
	 */
	@Operation(summary = "③ 지원서 통합 상세 조회",
			description = "지원서 화면 진입 시 필요한 모든 데이터(문항·인터뷰 이력·답변 현황)를 1회 호출로 반환.")
	@GetMapping("/{applicationId}")
	public ApiResponse<ApplicationDetailResponse> getApplicationDetail(
			@AuthenticationPrincipal String userId,
			@PathVariable Long applicationId) {
		return ApiResponse.ok(
				essayApplicationService.getApplicationDetail(UUID.fromString(userId), applicationId));
	}

	/**
	 * ④ STEP1 사전 인터뷰 대화. 인터뷰 이력이 없으면 seed 질문 자동 생성(부트스트랩), 있으면
	 * 요청의 stepOrder 위치에 답변 저장 후 다음 질문 생성. body 를 비운 채 호출하면 부트스트랩만
	 * 수행된다.
	 */
	@Operation(summary = "④ STEP1 사전 인터뷰 대화",
			description = "인터뷰 이력이 없으면 seed 질문 자동 생성(부트스트랩: body 비워서 호출), "
					+ "있으면 답변 저장 후 다음 질문 생성. LLM = Haiku 사용, 최대 5턴.")
	@PostMapping("/{applicationId}/questions/{questionId}/interview")
	public ApiResponse<InterviewAdvanceResponse> advanceInterview(
			@AuthenticationPrincipal String userId,
			@PathVariable Long applicationId,
			@PathVariable Long questionId,
			@RequestBody(required = false) InterviewAnswerRequest request) {
		InterviewAnswerRequest safeRequest = request != null
				? request
				: new InterviewAnswerRequest(null, null);
		return ApiResponse.ok(
				interviewService.advance(UUID.fromString(userId), applicationId, questionId, safeRequest));
	}

	/**
	 * ⑤ STEP2 답변 관리. action=DRAFT/SAVE/CONFIRM 로 세 동작을 통합 처리한다.
	 * CONFIRM 이 지원서의 마지막 미완료 문항을 완료시키면 essay 를 자동 COMPLETED 로 전환한다.
	 */
	@Operation(summary = "⑤ STEP2 답변 관리 (draft/save/confirm)",
			description = "action 파라미터로 세 동작 통합: DRAFT(LLM 초안 생성 = Sonnet), "
					+ "SAVE(임시저장), CONFIRM(완료 확정 + 전 문항 완료 시 essay 자동 COMPLETED).")
	@PutMapping("/{applicationId}/questions/{questionId}/answer")
	public ApiResponse<AnswerActionResponse> handleAnswer(
			@AuthenticationPrincipal String userId,
			@PathVariable Long applicationId,
			@PathVariable Long questionId,
			@Valid @RequestBody AnswerActionRequest request) {
		return ApiResponse.ok(
				answerService.handle(UUID.fromString(userId), applicationId, questionId, request));
	}
}
