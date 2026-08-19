package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.ExcelImportResult;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualRequest;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/*
장학금 일괄 편집용 엑셀 내보내기/업로드.

용도: 팀원들이 엑셀로 나눠 수정하고, 관리자가 취합본을 한 번에 반영한다.
공공 API 원본에 상세 URL·포스터 필드가 아예 없어 사람이 채우는 수밖에 없는데(계획 3단계),
한 건씩 화면에서 고치는 것보다 이쪽이 현실적이다.

설계 원칙
- ID 로만 매칭한다. 내보내기 시 ID 가 이미 채워져 나가므로 사람이 입력할 일이 없다.
- 빈 칸은 "변경 없음"이다. 지우려고 빈 칸을 둔 게 아니라 안 건드린 칸이 대부분이라,
  빈 칸을 null 로 덮으면 멀쩡한 데이터가 날아간다.
- 기본이 dry-run 이다. 일괄 쓰기는 되돌릴 수 없어 먼저 몇 건이 바뀌는지 보여준다.
- 메모리: t3.small(-Xmx1g)에 OOM 이력이 있어 파일 크기·행수 상한을 건다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScholarshipExcelService {

	/** 업로드 허용 최대 행수. 현재 장학금이 400건 미만이라 넉넉하다. */
	static final int MAX_ROWS = 1000;
	/** 업로드 허용 최대 파일 크기(1MB). POI 가 파일을 메모리에 올리므로 상한이 필요하다. */
	static final long MAX_FILE_BYTES = 1024L * 1024L;

	private static final String SHEET_NAME = "장학금";
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	/** 열 순서. 앞 3개는 참고용(수정해도 무시), 나머지가 편집 대상이다. */
	private static final String[] HEADERS = {
			"ID(수정금지)", "출처(참고)", "상태(참고)",
			"장학금명", "운영기관", "요약", "지원금액(원)", "선발인원",
			"모집시작일(yyyy-MM-dd HH:mm)", "모집종료일(yyyy-MM-dd HH:mm)", "상세URL"
	};
	private static final int COL_ID = 0;
	private static final int COL_TITLE = 3;
	private static final int COL_PROVIDER = 4;
	private static final int COL_SUMMARY = 5;
	private static final int COL_AMOUNT = 6;
	private static final int COL_SELECTION = 7;
	private static final int COL_START = 8;
	private static final int COL_END = 9;
	private static final int COL_URL = 10;

	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipManualService scholarshipManualService;

	public byte[] export() {
		List<Scholarship> scholarships = scholarshipRepository.findAllForExcelExport();

		try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
			 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet(SHEET_NAME);
			writeHeader(workbook, sheet);

			int rowIndex = 1;
			for (Scholarship scholarship : scholarships) {
				writeRow(sheet.createRow(rowIndex++), scholarship);
			}

			workbook.write(out);
			log.info("[ScholarshipExcel] 내보내기 {}건", scholarships.size());
			return out.toByteArray();
		} catch (IOException e) {
			log.error("[ScholarshipExcel] 내보내기 실패", e);
			throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * 업로드 반영. {@code dryRun} 이면 검증만 하고 DB 는 건드리지 않는다.
	 * 오류 행은 건너뛰고 계속 진행해, 한 줄이 잘못됐다고 전체가 막히지 않게 한다.
	 */
	public ExcelImportResult importFrom(MultipartFile file, boolean dryRun) {
		validateFile(file);

		List<ExcelImportResult.RowError> errors = new ArrayList<>();
		int applied = 0;
		int dataRows = 0;

		try (InputStream in = file.getInputStream(); Workbook workbook = new XSSFWorkbook(in)) {
			Sheet sheet = workbook.getSheetAt(0);
			int lastRow = sheet.getLastRowNum();
			if (lastRow > MAX_ROWS) {
				throw new CustomException(ErrorCode.EXCEL_TOO_MANY_ROWS);
			}

			for (int i = 1; i <= lastRow; i++) {
				Row row = sheet.getRow(i);
				if (isBlankRow(row)) {
					continue;
				}
				dataRows++;
				// 엑셀 화면의 행번호(1-based)로 보고해야 사람이 찾을 수 있다.
				int displayRow = i + 1;
				try {
					if (applyRow(row, dryRun)) {
						applied++;
					}
				} catch (CustomException e) {
					errors.add(new ExcelImportResult.RowError(displayRow, e.getErrorCode().getMessage()));
				} catch (RuntimeException e) {
					errors.add(new ExcelImportResult.RowError(displayRow, "처리 중 오류: " + e.getMessage()));
				}
			}
		} catch (IOException e) {
			log.warn("[ScholarshipExcel] 파일을 읽지 못했습니다", e);
			throw new CustomException(ErrorCode.EXCEL_PARSE_FAILED);
		}

		log.info("[ScholarshipExcel] 업로드 dryRun={} 대상={} 반영={} 오류={}",
				dryRun, dataRows, applied, errors.size());
		return new ExcelImportResult(dryRun, dataRows, applied, errors.size(), errors);
	}

	private void validateFile(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new CustomException(ErrorCode.EXCEL_FILE_REQUIRED);
		}
		if (file.getSize() > MAX_FILE_BYTES) {
			throw new CustomException(ErrorCode.EXCEL_FILE_TOO_LARGE);
		}
		String name = file.getOriginalFilename();
		if (name == null || !name.toLowerCase().endsWith(".xlsx")) {
			throw new CustomException(ErrorCode.EXCEL_INVALID_FORMAT);
		}
	}

	/** 한 행 반영. 바뀐 값이 하나도 없으면 false 를 돌려 "반영 건수"에서 뺀다. */
	private boolean applyRow(Row row, boolean dryRun) {
		Long id = readLong(row.getCell(COL_ID));
		if (id == null) {
			// 내보내기 파일에는 ID 가 항상 채워져 있다. 비어 있다면 사람이 새로 추가한 행이다.
			throw new CustomException(ErrorCode.EXCEL_ROW_ID_REQUIRED);
		}

		ScholarshipManualRequest request = new ScholarshipManualRequest(
				readText(row.getCell(COL_TITLE)),
				readText(row.getCell(COL_PROVIDER)),
				readText(row.getCell(COL_SUMMARY)),
				null,
				null,
				readDateTime(row.getCell(COL_START)),
				readDateTime(row.getCell(COL_END)),
				toInt(readLong(row.getCell(COL_SELECTION))),
				readLong(row.getCell(COL_AMOUNT)),
				readText(row.getCell(COL_URL)),
				null);

		if (isEmptyRequest(request)) {
			return false;
		}
		if (dryRun) {
			// 존재하지 않는 ID 를 미리 걸러내기 위해 조회까지는 한다.
			if (!scholarshipRepository.existsById(id)) {
				throw new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND);
			}
			return true;
		}
		scholarshipManualService.update(id, request);
		return true;
	}

	private boolean isEmptyRequest(ScholarshipManualRequest r) {
		return r.title() == null && r.provider() == null && r.summary() == null
				&& r.applicationStartAt() == null && r.applicationEndAt() == null
				&& r.selectionCount() == null && r.amount() == null && r.homepageUrl() == null;
	}

	// --- 엑셀 읽기 -------------------------------------------------------

	private boolean isBlankRow(Row row) {
		if (row == null) {
			return true;
		}
		for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
			if (StringUtils.hasText(readText(row.getCell(c)))) {
				return false;
			}
		}
		return true;
	}

	/** 빈 칸은 null 로 돌려준다. "변경 없음"의 표현이다. */
	private String readText(Cell cell) {
		if (cell == null) {
			return null;
		}
		String value = switch (cell.getCellType()) {
			case STRING -> cell.getStringCellValue();
			case NUMERIC -> DateUtil.isCellDateFormatted(cell)
					? cell.getLocalDateTimeCellValue().format(DATE_FORMAT)
					: trimTrailingZero(cell.getNumericCellValue());
			case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
			case FORMULA -> cell.getCellFormula();
			default -> null;
		};
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	/** 엑셀은 정수도 실수로 준다(1234.0). 뒤의 .0 을 떼야 숫자 파싱이 된다. */
	private String trimTrailingZero(double value) {
		if (value == Math.rint(value) && !Double.isInfinite(value)) {
			return String.valueOf((long) value);
		}
		return String.valueOf(value);
	}

	private Long readLong(Cell cell) {
		String text = readText(cell);
		if (text == null) {
			return null;
		}
		try {
			// 사람이 "1,000,000" 처럼 콤마를 넣는 경우가 흔하다.
			return Long.parseLong(text.replace(",", "").trim());
		} catch (NumberFormatException e) {
			throw new CustomException(ErrorCode.EXCEL_INVALID_NUMBER);
		}
	}

	private Integer toInt(Long value) {
		return value == null ? null : Math.toIntExact(value);
	}

	private LocalDateTime readDateTime(Cell cell) {
		if (cell == null) {
			return null;
		}
		if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
				&& DateUtil.isCellDateFormatted(cell)) {
			return cell.getLocalDateTimeCellValue();
		}
		String text = readText(cell);
		if (text == null) {
			return null;
		}
		try {
			return LocalDateTime.parse(text, DATE_FORMAT);
		} catch (DateTimeParseException ignored) {
			// 시간을 빼고 날짜만 적는 경우가 많다.
			try {
				return LocalDateTime.parse(text.substring(0, Math.min(10, text.length())) + " 00:00", DATE_FORMAT);
			} catch (RuntimeException e) {
				throw new CustomException(ErrorCode.EXCEL_INVALID_DATE);
			}
		}
	}

	// --- 엑셀 쓰기 -------------------------------------------------------

	private void writeHeader(Workbook workbook, Sheet sheet) {
		CellStyle style = workbook.createCellStyle();
		Font font = workbook.createFont();
		font.setBold(true);
		style.setFont(font);

		Row header = sheet.createRow(0);
		for (int i = 0; i < HEADERS.length; i++) {
			Cell cell = header.createCell(i);
			cell.setCellValue(HEADERS[i]);
			cell.setCellStyle(style);
		}
	}

	private void writeRow(Row row, Scholarship s) {
		row.createCell(COL_ID).setCellValue(s.getId());
		row.createCell(1).setCellValue(s.getPrimarySource() == null ? "MANUAL" : s.getPrimarySource());
		row.createCell(2).setCellValue(
				s.getRecruitmentStatus() == null ? "" : s.getRecruitmentStatus().name());
		row.createCell(COL_TITLE).setCellValue(s.getTitle());
		row.createCell(COL_PROVIDER).setCellValue(s.getProvider());
		row.createCell(COL_SUMMARY).setCellValue(s.getSummary());
		if (s.getAmount() != null) {
			row.createCell(COL_AMOUNT).setCellValue(s.getAmount());
		}
		if (s.getSelectionCount() != null) {
			row.createCell(COL_SELECTION).setCellValue(s.getSelectionCount());
		}
		row.createCell(COL_START).setCellValue(format(s.getApplicationStartAt()));
		row.createCell(COL_END).setCellValue(format(s.getApplicationEndAt()));
		row.createCell(COL_URL).setCellValue(s.getHomepageUrl());
	}

	private String format(LocalDateTime value) {
		return value == null ? "" : value.format(DATE_FORMAT);
	}
}
