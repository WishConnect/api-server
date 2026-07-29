package com.wishconnect.domain.scholarship.controller;

import com.wishconnect.domain.scholarship.collector.UnivNoticeCollector;
import com.wishconnect.domain.scholarship.dto.CollectResultResponse;
import com.wishconnect.domain.scholarship.dto.ConditionExtractionResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipSyncResponse;
import com.wishconnect.domain.scholarship.service.ConditionExtractionService;
import com.wishconnect.domain.scholarship.service.ScholarshipSyncService;
import com.wishconnect.global.common.ApiResponse;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장학금 데이터 파이프라인의 수동 트리거(운영/관리용)를 모은 컨트롤러.
 *
 * <p>모두 <b>ADMIN 전용</b>이다. 외부 API 호출·크롤링·LLM 호출을 유발해
 * 공공데이터 쿼터 소진, 대상 사이트 부하, LLM 과금으로 이어질 수 있어
 * 로그인만으로는 호출할 수 없어야 한다.
 */
@Tag(name = "장학금 - 관리", description = "수집·동기화·조건추출 수동 트리거 (ADMIN 전용)")
@RestController
@RequestMapping("/api/v1/scholarships")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Profile("!test")
public class ScholarshipAdminController {

	private final ScholarshipSyncService scholarshipSyncService;
	private final UnivNoticeCollector univNoticeCollector;
	private final ConditionExtractionService conditionExtractionService;

	@Operation(summary = "공공데이터 수동 동기화",
			description = "한국장학재단 학자금지원정보를 수동으로 동기화한다. (ADMIN 전용)")
	@PostMapping("/sync")
	public ApiResponse<ScholarshipSyncResponse> syncScholarships() {
		return ApiResponse.ok(scholarshipSyncService.sync());
	}

	@Operation(summary = "대학 장학공지 크롤링 수집",
			description = "code 는 application.yml 의 사이트 코드(konkuk, yonsei 등). (ADMIN 전용)")
	@PostMapping("/collect/univ/{code}")
	public ApiResponse<CollectResultResponse> collectUniv(
			@PathVariable String code,
			@RequestParam(defaultValue = "1") int pages) {
		return ApiResponse.ok(univNoticeCollector.collectByCode(code, pages)
				.orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT)));
	}

	@Operation(summary = "LLM 조건 구조화 추출",
			description = "수집된 공고 본문에서 지원 자격조건을 구조화한다. sync 후 실행 권장. (ADMIN 전용)")
	@PostMapping("/conditions/extract")
	public ApiResponse<ConditionExtractionResponse> extractConditions() {
		return ApiResponse.ok(conditionExtractionService.extract());
	}
}
