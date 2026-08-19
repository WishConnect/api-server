package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "다중 시트 장학금 신규 등록 엑셀 처리 결과")
public record ManualExcelImportResult(
		boolean dryRun,
		int totalRows,
		int createdRows,
		int errorCount,
		List<RowError> errors
) {
	public record RowError(int rowNumber, String clientKey, String reason) {
	}
}
