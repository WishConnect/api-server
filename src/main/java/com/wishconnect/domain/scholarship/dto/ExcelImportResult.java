package com.wishconnect.domain.scholarship.dto;

import java.util.List;

/**
 * 엑셀 업로드 결과.
 *
 * <p>{@code dryRun} 이 true 면 검증만 한 것이고 DB 는 그대로다.
 * 오류 행은 건너뛰고 나머지를 계속 처리하므로, 한 줄이 잘못돼도 전체가 막히지 않는다.
 */
public record ExcelImportResult(
		boolean dryRun,
		int totalRows,
		int appliedRows,
		int errorCount,
		List<RowError> errors
) {

	/** {@code rowNumber} 는 엑셀 화면에 보이는 행번호(1-based)다. 사람이 바로 찾아갈 수 있어야 한다. */
	public record RowError(int rowNumber, String reason) {
	}
}
