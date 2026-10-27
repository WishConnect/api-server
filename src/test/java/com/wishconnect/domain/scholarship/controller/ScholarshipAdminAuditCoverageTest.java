package com.wishconnect.domain.scholarship.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.dto.ExcelImportResult;
import com.wishconnect.domain.scholarship.dto.NoticeParsingResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipAdminChangeResult;
import com.wishconnect.domain.scholarship.dto.ScholarshipAdminSnapshot;
import com.wishconnect.domain.scholarship.service.ScholarshipChangeSummarizer;
import com.wishconnect.domain.scholarship.service.ScholarshipExcelService;
import com.wishconnect.domain.scholarship.service.UnivNoticeLlmParsingService;
import com.wishconnect.global.audit.AdminAction;
import com.wishconnect.global.audit.AdminAuditDetails;
import com.wishconnect.global.audit.AdminAuditLogService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

/** LLM 수동 실행과 엑셀 행별 기록이 작업 기록에 남는지. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScholarshipAdminAuditCoverageTest {

	@Mock private UnivNoticeLlmParsingService parsingService;
	@Mock private ScholarshipExcelService excelService;
	@Mock private ScholarshipChangeSummarizer summarizer;
	@Mock private AdminAuditLogService auditLogService;
	@InjectMocks private ScholarshipAdminController controller;

	private final UUID actor = UUID.randomUUID();

	@Test
	@DisplayName("선택 LLM 재처리는 LLM_PARSE_TRIGGER 로 원문·건수를 남긴다(원문 하나면 대상으로)")
	void recordsLlmReprocess() {
		given(parsingService.parse(anyInt(), anyBoolean(), anyBoolean(), anyList(), anyBoolean()))
				.willReturn(new NoticeParsingResponse(1, 1, 0, 0, false, List.of()));

		controller.parseUnivNoticesWithLlm(actor.toString(), 1, true, false, List.of(8766L), false);

		verify(auditLogService).record(eq(actor), eq(AdminAction.LLM_PARSE_TRIGGER), eq("RAW_SCHOLARSHIP"), eq(8766L),
				contains("선택 재처리 · 대상 1 · 정제 1 · 건너뜀 0 · 실패 0 · limit=1 · reparse · 완료 건 포함 · 원문 #8766"));
	}

	@Test
	@DisplayName("공공데이터 조건 보강도 dryRun 표시와 함께 남긴다")
	void recordsKosafConditions() {
		given(parsingService.parseKosafConditions(5, true)).willReturn(new NoticeParsingResponse(0, 0, 0, 0, true, List.of()));

		controller.parseKosafConditions(actor.toString(), 5, true);

		verify(auditLogService).record(eq(actor), eq(AdminAction.KOSAF_CONDITION_TRIGGER), isNull(), isNull(),
				contains("공공데이터 조건 보강(dryRun) · 대상 0"));
	}

	@Test
	@DisplayName("엑셀 일괄 수정은 반영된 행마다 SCHOLARSHIP_UPDATE 전후 스냅샷을 남기고, 파일 요약도 남긴다")
	void recordsExcelRows() {
		ScholarshipAdminSnapshot before = mock(ScholarshipAdminSnapshot.class);
		ScholarshipAdminSnapshot after = mock(ScholarshipAdminSnapshot.class);
		given(summarizer.summarize(before, after)).willReturn("변경: 제목");
		given(excelService.importFrom(any(), eq(false), any())).willAnswer(invocation -> {
			ScholarshipExcelService.RowUpdateListener listener = invocation.getArgument(2);
			listener.updated(3, 101L, new ScholarshipAdminChangeResult(null, before, after));
			return new ExcelImportResult(false, 1, 1, 0, List.of());
		});

		controller.importExcel(actor.toString(), new MockMultipartFile("file", "edit.xlsx", null, new byte[] {1}), false);

		verify(auditLogService).recordChange(actor, AdminAction.SCHOLARSHIP_UPDATE, "SCHOLARSHIP", 101L,
				"엑셀 일괄 수정 3행(파일=edit.xlsx) · 변경: 제목", before, after);
		verify(auditLogService).record(eq(actor), eq(AdminAction.EXCEL_IMPORT), isNull(), isNull(), contains("반영 1행"));
	}

	@Test
	@DisplayName("엑셀 검사(dryRun)는 아무것도 남기지 않는다")
	void dryRunRecordsNothing() {
		given(excelService.importFrom(any(), eq(true), any())).willReturn(new ExcelImportResult(true, 1, 1, 0, List.of()));

		controller.importExcel(actor.toString(), new MockMultipartFile("file", "edit.xlsx", null, new byte[] {1}), true);

		verify(auditLogService, never()).recordChange(any(), any(), any(), any(), any(), any(), any());
		verify(auditLogService, never()).record(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("신고·문의 처리 요약은 상태 뒤에 답변을 붙인다(답변이 없으면 상태만)")
	void resolutionDetail() {
		assertThat(AdminAuditDetails.resolution("RESOLVED", " 마감일을 고쳤습니다. ")).isEqualTo("RESOLVED · 답변: 마감일을 고쳤습니다.");
		assertThat(AdminAuditDetails.resolution("REJECTED", "  ")).isEqualTo("REJECTED");
	}
}
