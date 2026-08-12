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
	 * ④ STEP1 사전 인터뷰. 인터뷰 이력이 없으면 문항별 사전 질문 5개를 한 번에 생성해 전부 반환하고,
	 * 있으면 요청에 담긴 답변들을 저장한다. body 를 비운 채 호출하면 질문 생성(또는 현재 상태 조회)만
	 * 수행된다. 응답에는 항상 질문 전체와 답변 상태가 함께 담긴다.
	 */
	@Operation(summary = "④ STEP1 사전 인터뷰 (질문 일괄 생성 / 답변 저장)",
			description = """
					문항(카테고리)당 사전 질문 5개를 한 번에 생성하고, 답변을 저장한다.
					인터뷰 시작이든 답변 저장이든 **응답 형태는 항상 동일**하다. 질문 전체와 현재 답변
					상태가 함께 내려오므로 응답 하나로 화면을 다시 그리면 된다.

					**호출 방법**
					- 인터뷰 시작: body 없이(또는 answers=null) 호출 → 질문 5개를 생성해 전부 반환.
					  이 호출만 LLM(Haiku)을 타므로 느리다. 이미 질문이 있으면 재생성하지 않고
					  현재 상태를 반환하므로 여러 번 호출해도 안전하다.
					- 답변 저장: answers 배열에 담아 호출 → LLM 호출 없음.
					  부분 제출 가능(1건씩 자동저장 OK), 같은 stepOrder 재제출 시 덮어쓴다(수정 지원).
					  answerText 가 비어 있는 항목은 건너뛰므로 기존 답변이 지워지지 않는다.

					**STEP2 진입 판단**
					- canGenerateDraft=true (답변 1건 이상) → ⑤ DRAFT 호출 가능.
					  5개를 다 채우지 않아도 넘어갈 수 있다.
					- isInterviewComplete=true → 5개 전부 답변 완료.

					**주의**: totalCount 는 보통 5지만 LLM 응답에 따라 더 적을 수 있다.
					화면은 questions 배열 길이 기준으로 그릴 것.

					**에러**
					- 400 : stepOrder 누락 / 존재하지 않는 stepOrder / 한 요청 내 stepOrder 중복
					- 404 : 지원서 또는 문항 없음 (타인 소유 포함)
					- 503 : LLM 이 질문을 하나도 생성하지 못함. 재시도 안내 필요
					""")
	@PostMapping("/{applicationId}/questions/{questionId}/interview")
	public ApiResponse<InterviewAdvanceResponse> advanceInterview(
			@AuthenticationPrincipal String userId,
			@PathVariable Long applicationId,
			@PathVariable Long questionId,
			@RequestBody(required = false) InterviewAnswerRequest request) {
		InterviewAnswerRequest safeRequest = request != null
				? request
				: new InterviewAnswerRequest(null);
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
