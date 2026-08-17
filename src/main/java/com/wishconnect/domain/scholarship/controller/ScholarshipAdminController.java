package com.wishconnect.domain.scholarship.controller;

import com.wishconnect.domain.scholarship.collector.DedicatedNoticeCollectors;
import com.wishconnect.domain.scholarship.collector.UnivNoticeCollector;
import com.wishconnect.domain.scholarship.dto.AdminOverviewResponse;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipRow;
import com.wishconnect.domain.scholarship.dto.CollectResultResponse;
import com.wishconnect.domain.scholarship.dto.ConditionExtractionResponse;
import com.wishconnect.domain.scholarship.dto.EnrichmentResult;
import com.wishconnect.domain.scholarship.dto.ExcelImportResult;
import com.wishconnect.domain.scholarship.dto.ReportResolveRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipReportResponse;
import com.wishconnect.domain.scholarship.entity.ReportStatus;
import com.wishconnect.domain.scholarship.dto.ScholarshipSyncResponse;
import com.wishconnect.domain.scholarship.service.ConditionExtractionService;
import com.wishconnect.domain.scholarship.service.ScholarshipAdminOverviewService;
import com.wishconnect.domain.scholarship.service.ScholarshipEnrichmentService;
import com.wishconnect.domain.scholarship.service.ScholarshipExcelService;
import com.wishconnect.domain.scholarship.service.ScholarshipManualService;
import com.wishconnect.domain.scholarship.service.ScholarshipReportService;
import com.wishconnect.domain.scholarship.service.ScholarshipSyncService;
import com.wishconnect.global.audit.AdminAction;
import com.wishconnect.global.audit.AdminAuditLogService;
import com.wishconnect.global.common.ApiResponse;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
	private final DedicatedNoticeCollectors dedicatedNoticeCollectors;
	private final ConditionExtractionService conditionExtractionService;
	private final ScholarshipManualService scholarshipManualService;
	private final ScholarshipReportService scholarshipReportService;
	private final ScholarshipAdminOverviewService scholarshipAdminOverviewService;
	private final ScholarshipExcelService scholarshipExcelService;
	private final ScholarshipEnrichmentService scholarshipEnrichmentService;
	private final AdminAuditLogService adminAuditLogService;

	@Operation(summary = "데이터 현황 요약",
			description = "원본 파싱 상태와 출처별 파싱 품질을 집계한다. 수집기를 고쳤을 때 "
					+ "채움률이 오르는지로 효과를 확인한다. (ADMIN 전용)")
	@GetMapping("/admin/overview")
	public ApiResponse<AdminOverviewResponse> adminOverview() {
		return ApiResponse.ok(scholarshipAdminOverviewService.overview());
	}

	@Operation(summary = "최근 수집 장학금 목록",
			description = "최근에 들어온 순서로 파싱 결과를 훑어본다. source 로 출처를 좁힐 수 있다. (ADMIN 전용)")
	@GetMapping("/admin/recent")
	public ApiResponse<List<AdminScholarshipRow>> adminRecent(
			@RequestParam(required = false) String source,
			@RequestParam(required = false) Integer size) {
		return ApiResponse.ok(scholarshipAdminOverviewService.recent(source, size));
	}

	@Operation(summary = "장학금 엑셀 내보내기",
			description = "팀원들이 나눠 수정할 수 있게 xlsx 로 내려받는다. ID 는 채워져 나가므로 "
					+ "수정 후 그대로 업로드하면 된다. (ADMIN 전용)")
	@GetMapping("/admin/excel")
	public ResponseEntity<byte[]> exportExcel() {
		byte[] body = scholarshipExcelService.export();
		String filename = "scholarships-" + LocalDate.now() + ".xlsx";
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).toString())
				.contentType(MediaType.parseMediaType(
						"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
				.body(body);
	}

	@Operation(summary = "장학금 엑셀 일괄 반영",
			description = "내보내기 받은 파일을 수정해 올린다. 빈 칸은 변경하지 않는다. "
					+ "기본은 검증만 하는 dryRun 이며, dryRun=false 로 보내야 실제로 반영된다. (ADMIN 전용)")
	@PostMapping("/admin/excel")
	public ApiResponse<ExcelImportResult> importExcel(
			@AuthenticationPrincipal String actorId,
			@RequestPart("file") MultipartFile file,
			@RequestParam(defaultValue = "true") boolean dryRun) {
		ExcelImportResult result = scholarshipExcelService.importFrom(file, dryRun);
		// dryRun 은 DB 를 건드리지 않으므로 남기지 않는다. 실제 반영만 기록한다.
		if (!dryRun) {
			adminAuditLogService.record(UUID.fromString(actorId), AdminAction.EXCEL_IMPORT, null, null,
					"반영 %d행, 오류 %d행, 파일=%s"
							.formatted(result.appliedRows(), result.errorCount(), file.getOriginalFilename()));
		}
		return ApiResponse.ok(result);
	}

	@Operation(summary = "공공데이터 수동 동기화",
			description = "한국장학재단 학자금지원정보를 수동으로 동기화한다. (ADMIN 전용)")
	@PostMapping("/sync")
	public ApiResponse<ScholarshipSyncResponse> syncScholarships(@AuthenticationPrincipal String actorId) {
		ScholarshipSyncResponse result = scholarshipSyncService.sync();
		adminAuditLogService.record(UUID.fromString(actorId), AdminAction.SYNC_TRIGGER, null, null,
				"수집 %d건, 저장 %d건, 실패 %d건"
						.formatted(result.fetchedCount(), result.savedCount(), result.failedCount()));
		return ApiResponse.ok(result);
	}

	@Operation(summary = "대학 장학공지 크롤링 수집",
			description = "code 는 application.yml 의 사이트 코드(konkuk, yonsei 등) 또는 전용 수집기 코드"
					+ "(korea, sogang, skku, hanyang, cau, khu). 게시판 구조가 공통 규칙으로 묶이지 않는 대학은"
					+ " 전용 수집기로 처리하며, 양쪽에서 코드를 찾지 못하면 400. (ADMIN 전용)")
	@PostMapping("/collect/univ/{code}")
	public ApiResponse<CollectResultResponse> collectUniv(
			@AuthenticationPrincipal String actorId,
			@PathVariable String code,
			@RequestParam(defaultValue = "1") int pages) {
		// 설정 기반 수집기를 먼저 보고, 없으면 대학별 전용 수집기로 넘긴다.
		CollectResultResponse result = univNoticeCollector.collectByCode(code, pages)
				.or(() -> dedicatedNoticeCollectors.collectByCode(code, pages))
				.orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT));
		adminAuditLogService.record(UUID.fromString(actorId), AdminAction.COLLECT_TRIGGER, null, null,
				"%s 수집 %d건, 저장 %d건".formatted(code, result.fetchedCount(), result.savedCount()));
		return ApiResponse.ok(result);
	}

	@Operation(summary = "상세페이지·첨부·포스터 자동 보완",
			description = "공공데이터가 주지 않는 상세 URL·제출서류 첨부·포스터를 검색+크롤링으로 채운다. "
					+ "신뢰도가 낮으면 반영하지 않고 건너뛴 목록으로 돌려준다. 외부 사이트를 호출하므로 "
					+ "limit 을 크게 주지 말 것. (ADMIN 전용)")
	@PostMapping("/enrich")
	public ApiResponse<EnrichmentResult> enrich(
			@AuthenticationPrincipal String actorId,
			@RequestParam(defaultValue = "20") int limit) {
		EnrichmentResult result = scholarshipEnrichmentService.enrich(limit);
		adminAuditLogService.record(UUID.fromString(actorId), AdminAction.ENRICH_TRIGGER, null, null,
				"대상 %d건, 상세URL %d건, 이미지 %d건, 첨부 %d건, 건너뜀 %d건".formatted(
						result.targetCount(), result.detailUrlFound(), result.imageSaved(),
						result.documentLinked(), result.skippedCount()));
		return ApiResponse.ok(result);
	}

	@Operation(summary = "LLM 조건 구조화 추출",
			description = "수집된 공고 본문에서 지원 자격조건을 구조화한다. sync 후 실행 권장. (ADMIN 전용)")
	@PostMapping("/conditions/extract")
	public ApiResponse<ConditionExtractionResponse> extractConditions(
			@AuthenticationPrincipal String actorId) {
		ConditionExtractionResponse result = conditionExtractionService.extract();
		adminAuditLogService.record(UUID.fromString(actorId), AdminAction.CONDITION_EXTRACT_TRIGGER,
				null, null, "대상 %d건, 추출 %d건".formatted(result.targetCount(), result.extractedCount()));
		return ApiResponse.ok(result);
	}

	@Operation(summary = "장학금 수기 등록",
			description = "주최사 제보 등 수집이 놓친 공고를 직접 등록한다. "
					+ "동기화 배치가 덮어쓰지 않도록 별도 출처(MANUAL)로 저장된다. (ADMIN 전용)")
	@PostMapping("/manual")
	public ResponseEntity<ApiResponse<ScholarshipManualResponse>> createManual(
			@AuthenticationPrincipal String actorId,
			@Valid @RequestBody ScholarshipManualRequest.Create request) {
		ScholarshipManualResponse result = scholarshipManualService.create(request);
		adminAuditLogService.record(UUID.fromString(actorId), AdminAction.SCHOLARSHIP_CREATE,
				"SCHOLARSHIP", result.scholarshipId(), result.title());
		return ResponseEntity.status(201).body(ApiResponse.ok(result));
	}

	@Operation(summary = "장학금 직접 수정",
			description = "보낸 필드만 반영한다(부분 수정). 수집분·수기분 모두 대상. (ADMIN 전용)")
	@PatchMapping("/manual/{scholarshipId}")
	public ApiResponse<ScholarshipManualResponse> updateManual(
			@AuthenticationPrincipal String actorId,
			@PathVariable Long scholarshipId,
			@Valid @RequestBody ScholarshipManualRequest request) {
		ScholarshipManualResponse result = scholarshipManualService.update(scholarshipId, request);
		adminAuditLogService.record(UUID.fromString(actorId), AdminAction.SCHOLARSHIP_UPDATE,
				"SCHOLARSHIP", scholarshipId, result.title());
		return ApiResponse.ok(result);
	}

	@Operation(summary = "장학금 내리기",
			description = "오등록으로 확인된 장학금을 목록에서 내린다(soft delete). (ADMIN 전용)")
	@DeleteMapping("/manual/{scholarshipId}")
	public ApiResponse<Void> deleteManual(
			@AuthenticationPrincipal String actorId,
			@PathVariable Long scholarshipId) {
		scholarshipManualService.delete(scholarshipId);
		adminAuditLogService.record(UUID.fromString(actorId), AdminAction.SCHOLARSHIP_DELETE,
				"SCHOLARSHIP", scholarshipId, null);
		return ApiResponse.ok();
	}

	@Operation(summary = "오등록 신고 목록",
			description = "status 미지정 시 전체를 최신순으로 준다. (ADMIN 전용)")
	@GetMapping("/reports")
	public ApiResponse<Page<ScholarshipReportResponse>> reports(
			@RequestParam(required = false) ReportStatus status,
			Pageable pageable) {
		return ApiResponse.ok(scholarshipReportService.findAll(status, pageable));
	}

	@Operation(summary = "오등록 신고 처리",
			description = "신고 상태만 바꾼다. 데이터 수정은 /manual/{id} 로 한다. (ADMIN 전용)")
	@PatchMapping("/reports/{reportId}")
	public ApiResponse<ScholarshipReportResponse> resolveReport(
			@AuthenticationPrincipal String actorId,
			@PathVariable Long reportId,
			@Valid @RequestBody ReportResolveRequest request) {
		ScholarshipReportResponse result = scholarshipReportService.resolve(reportId, request);
		adminAuditLogService.record(UUID.fromString(actorId), AdminAction.REPORT_RESOLVE,
				"REPORT", reportId, String.valueOf(request.status()));
		return ApiResponse.ok(result);
	}
}
