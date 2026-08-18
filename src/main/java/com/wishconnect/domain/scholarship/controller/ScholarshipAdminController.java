package com.wishconnect.domain.scholarship.controller;

import com.wishconnect.domain.scholarship.collector.DedicatedNoticeCollectors;
import com.wishconnect.domain.scholarship.collector.UnivNoticeCollector;
import com.wishconnect.domain.scholarship.dto.AdminOverviewResponse;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipRow;
import com.wishconnect.domain.scholarship.dto.CollectResultResponse;
import com.wishconnect.domain.scholarship.dto.MergeCandidateResponse;
import com.wishconnect.domain.scholarship.dto.MergeDetectionResponse;
import com.wishconnect.domain.scholarship.dto.MergeResultResponse;
import com.wishconnect.domain.scholarship.entity.MergeCandidateStatus;
import com.wishconnect.domain.scholarship.service.ScholarshipDedupService;
import com.wishconnect.domain.scholarship.dto.NoticeParsingResponse;
import com.wishconnect.domain.scholarship.dto.ConditionExtractionResponse;
import com.wishconnect.domain.scholarship.dto.ConditionRefBackfillResponse;
import com.wishconnect.domain.scholarship.dto.EnrichmentResult;
import com.wishconnect.domain.scholarship.dto.ExcelImportResult;
import com.wishconnect.domain.scholarship.dto.ReportResolveRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipReportResponse;
import com.wishconnect.domain.scholarship.entity.ReportStatus;
import com.wishconnect.domain.scholarship.dto.ScholarshipSyncResponse;
import com.wishconnect.domain.scholarship.service.ConditionExtractionService;
import com.wishconnect.domain.scholarship.service.ConditionRefBackfillService;
import com.wishconnect.domain.scholarship.service.UnivNoticeLlmParsingService;
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
	private final ConditionRefBackfillService conditionRefBackfillService;
	private final UnivNoticeLlmParsingService univNoticeLlmParsingService;
	private final ScholarshipDedupService scholarshipDedupService;
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

	@Operation(summary = "중복 장학금 후보 탐지",
			description = """
					같은 장학금이 중복 등록된 것을 찾아 승인 큐에 올린다.
					**이 단계에서는 아무것도 병합하지 않는다.** 사람이 승인해야 실제 병합이 일어난다.

					제목을 정규화해 같은 공고일 가능성이 있는 것끼리 먼저 묶고(규칙), 묶인 그룹만
					LLM 에 넘긴다. 모든 쌍을 LLM 에 물으면 호출이 O(n^2) 로 폭발하기 때문이다.
					실측(로컬 86건): 후보그룹 5개 → LLM 호출 5회.

					**주의**: 그룹 수만큼 LLM 크레딧을 소모한다. (ADMIN 전용)
					""")
	@PostMapping("/merge/detect")
	public ApiResponse<MergeDetectionResponse> detectMergeCandidates(
			@AuthenticationPrincipal String actorId,
			@RequestParam(defaultValue = "200") int limit) {
		MergeDetectionResponse result = scholarshipDedupService.detect(limit);
		adminAuditLogService.record(UUID.fromString(actorId), AdminAction.MERGE_DETECT_TRIGGER,
				null, null, "검사 %d건, 그룹 %d개, 신규후보 %d건, 실패 %d건".formatted(
						result.scannedCount(), result.groupCount(),
						result.candidateCount(), result.failedCount()));
		return ApiResponse.ok(result);
	}

	@Operation(summary = "중복 장학금 후보 목록",
			description = """
					승인 대기(PENDING) 또는 처리 완료된 병합 후보를 조회한다.
					두 장학금의 제목·기관·기간·금액·출처를 나란히 반환하므로 사람이 비교해 판단할 수 있다.

					캠퍼스만 다른 별개 모집이 후보로 올라오는 경우가 있다(실측: 복지장학금 서울/다빈치).
					승인 전에 반드시 눈으로 확인할 것. (ADMIN 전용)
					""")
	@GetMapping("/merge/candidates")
	public ApiResponse<MergeCandidateResponse> listMergeCandidates(
			@RequestParam(defaultValue = "PENDING") MergeCandidateStatus status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(scholarshipDedupService.list(status, page, size));
	}

	@Operation(summary = "중복 장학금 병합 승인",
			description = """
					후보를 승인해 실제로 병합한다. **되돌리기 어려운 작업이다.**

					duplicate 를 참조하던 스크랩·자소서·신고·알림이력·추천·원본·일정이 primary 로 옮겨가고,
					duplicate 는 소프트 삭제된다(행은 남아 이력 추적 가능). 조건·서류는 재파싱으로 다시
					만들어지는 파생 데이터라 옮기지 않고 지운다.

					같은 사용자가 양쪽을 스크랩했다면 중복 행을 지운 뒤 옮긴다. 자소서는 사용자가 직접 쓴
					글이므로 중복을 지우지 않고 둘 다 남긴다. (ADMIN 전용)
					""")
	@PostMapping("/merge/candidates/{candidateId}/approve")
	public ApiResponse<MergeResultResponse> approveMerge(
			@AuthenticationPrincipal String actorId,
			@PathVariable Long candidateId) {
		UUID reviewer = UUID.fromString(actorId);
		MergeResultResponse result = scholarshipDedupService.approve(candidateId, reviewer);
		adminAuditLogService.record(reviewer, AdminAction.SCHOLARSHIP_MERGE,
				"SCHOLARSHIP", result.primaryId(),
				"중복 %d 를 %d 로 병합. %s".formatted(
						result.duplicateId(), result.primaryId(), result.moved()));
		return ApiResponse.ok(result);
	}

	@Operation(summary = "중복 장학금 후보 반려",
			description = "중복이 아니라고 판정한다. 같은 쌍이 다음 탐지 배치에서 다시 올라오지 않는다. (ADMIN 전용)")
	@PostMapping("/merge/candidates/{candidateId}/reject")
	public ApiResponse<MergeResultResponse> rejectMerge(
			@AuthenticationPrincipal String actorId,
			@PathVariable Long candidateId,
			@RequestParam(required = false) String note) {
		UUID reviewer = UUID.fromString(actorId);
		MergeResultResponse result = scholarshipDedupService.reject(candidateId, reviewer, note);
		adminAuditLogService.record(reviewer, AdminAction.SCHOLARSHIP_MERGE_REJECT,
				"SCHOLARSHIP", result.primaryId(),
				"중복 후보 %d 반려 (%d vs %d). %s".formatted(
						candidateId, result.primaryId(), result.duplicateId(),
						note == null ? "" : note));
		return ApiResponse.ok(result);
	}

	@Operation(summary = "대학 장학공지 LLM 파싱",
			description = """
					대학 크롤링 공고(source=UNIV_*)의 raw_html 본문을 LLM(Haiku)으로 구조화해
					scholarship 으로 정제한다. 공공데이터 포털은 응답이 이미 구조화돼 있어
					이 경로를 타지 않는다(기존 /sync 가 처리).

					**파라미터**
					- limit: 처리 건수 (1~100, 기본 20). 크레딧 소진을 막는 상한이다.
					- reparse: true 면 이미 파싱된 것까지 다시 파싱해 덮어쓴다.
					  정규식으로 잘못 파싱된 기존 데이터를 정정할 때 쓴다.
					- rawIds: 지정하면 그 공지들만 처리한다(쉼표 구분). 프롬프트 버전 필터를 건너뛰므로
					  이미 파싱한 공지도 다시 돌릴 수 있다. 추출기를 고쳐 같은 공지의 결과가
					  달라졌을 때 쓴다.
					- skipComplete: 기본 true. 이미 제목·마감일이 제대로 들어간 공고는 건너뛴다.
					  결과가 좋아질 여지가 없는 건에 크레딧을 쓰지 않기 위해서다.
					  프롬프트를 크게 바꿔 전부 다시 보고 싶을 때만 false.
					- dryRun: true 면 DB 에 쓰지 않고 결과만 반환한다.
					  응답의 beforePeriod(기존 정규식) / afterPeriod(LLM) 를 사람이 비교해
					  전환 여부를 판단하는 용도다.

					**주의**: LLM 크레딧을 소모한다. dryRun 도 호출은 실제로 일어난다. (ADMIN 전용)
					""")
	@PostMapping("/parse/univ-llm")
	public ApiResponse<NoticeParsingResponse> parseUnivNoticesWithLlm(
			@RequestParam(defaultValue = "20") int limit,
			@RequestParam(defaultValue = "false") boolean reparse,
			@RequestParam(defaultValue = "false") boolean dryRun,
			@RequestParam(required = false) List<Long> rawIds,
			@RequestParam(defaultValue = "true") boolean skipComplete) {
		return ApiResponse.ok(univNoticeLlmParsingService.parse(
				limit, reparse, dryRun, rawIds == null ? List.of() : rawIds, skipComplete));
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

	@Operation(summary = "공공데이터 조건 마스터 참조 채우기",
			description = """
					이미 저장된 공공데이터 조건(지역·특정자격·전공계열·지원성격)의 원문에서 마스터 라벨을
					찾아 참조를 채운다. 참조가 없으면 매칭이 전부 "판정 불가"로 넘어가므로,
					지역·자격·전공 매칭을 켜기 전에 한 번 돌려야 한다.

					**LLM 을 쓰지 않는다.** 공공데이터는 이미 필드가 나뉘어 있어 규칙으로 충분하고,
					규칙은 같은 입력에 같은 답을 내므로 몇 번을 다시 돌려도 결과가 흔들리지 않는다.
					참조가 이미 있는 조건은 건드리지 않는다(대학공지 LLM 결과를 덮어쓰지 않기 위해서다).

					**파라미터**
					- limit: 처리 건수 (1~1000, 기본 200). (ADMIN 전용)
					""")
	@PostMapping("/conditions/refs")
	public ApiResponse<ConditionRefBackfillResponse> backfillConditionRefs(
			@AuthenticationPrincipal String actorId,
			@RequestParam(defaultValue = "200") int limit) {
		ConditionRefBackfillResponse result = conditionRefBackfillService.backfill(Math.max(1, Math.min(limit, 1000)));
		adminAuditLogService.record(UUID.fromString(actorId), AdminAction.CONDITION_REF_BACKFILL,
				null, null, "대상 %d건, 채움 %d건, 참조 %d개"
						.formatted(result.targetCount(), result.filledCount(), result.refCount()));
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
