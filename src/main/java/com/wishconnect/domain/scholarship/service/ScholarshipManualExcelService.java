package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.ManualExcelImportResult;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualFullRequest;
import com.wishconnect.domain.scholarship.entity.ConditionNecessity;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.entity.SubmissionChannel;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/** 신규 장학금을 관련 데이터까지 함께 등록하는 4개 시트 엑셀 처리기. */
@Service
@RequiredArgsConstructor
public class ScholarshipManualExcelService {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final String MAIN = "장학금";
	private static final String CONDITIONS = "조건";
	private static final String DOCUMENTS = "제출서류";
	private static final String IMAGES = "이미지";
	private static final int MAX_ROWS = 1000;
	private static final long MAX_BYTES = 1024L * 1024L;

	private static final String[] MAIN_HEADERS = {
			"임시키*", "장학금명*", "운영기관", "유형", "모집상태", "모집시작(yyyy-MM-dd HH:mm)",
			"모집종료(yyyy-MM-dd HH:mm)", "지원금액(원)", "선발인원", "요약", "상세설명",
			"homepage_url", "상세페이지URL", "제출경로", "제출방법", "제출근거", "연락처",
			"자기소개서", "자기소개서근거", "면접", "면접근거", "원문URL", "원문HTML"
	};
	private static final String[] CONDITION_HEADERS = {
			"임시키*", "조건유형*", "필수여부", "연산자", "원문*", "숫자값", "최대값", "참조라벨(쉼표구분)"
	};
	private static final String[] DOCUMENT_HEADERS = {
			"임시키*", "서류명*", "자기소개서여부", "표시순서", "다운로드URL"
	};
	private static final String[] IMAGE_HEADERS = {"임시키*", "이미지원본URL"};

	private final ScholarshipManualAggregateService aggregateService;

	public byte[] template() {
		try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			writeHeader(workbook.createSheet(MAIN), MAIN_HEADERS);
			writeHeader(workbook.createSheet(CONDITIONS), CONDITION_HEADERS);
			writeHeader(workbook.createSheet(DOCUMENTS), DOCUMENT_HEADERS);
			writeHeader(workbook.createSheet(IMAGES), IMAGE_HEADERS);
			workbook.write(output);
			return output.toByteArray();
		} catch (IOException e) {
			throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
		}
	}

	public ManualExcelImportResult importFile(MultipartFile file, boolean dryRun) {
		validateFile(file);
		List<ManualExcelImportResult.RowError> errors = new ArrayList<>();
		int total = 0;
		int created = 0;
		try (InputStream input = file.getInputStream(); Workbook workbook = new XSSFWorkbook(input)) {
			Sheet main = workbook.getSheet(MAIN);
			if (main == null) {
				throw new CustomException(ErrorCode.EXCEL_PARSE_FAILED);
			}
			Map<String, List<ScholarshipManualFullRequest.Condition>> conditions = readConditions(workbook);
			Map<String, List<ScholarshipManualFullRequest.Document>> documents = readDocuments(workbook);
			Map<String, String> images = readImages(workbook);
			if (main.getLastRowNum() > MAX_ROWS) {
				throw new CustomException(ErrorCode.EXCEL_TOO_MANY_ROWS);
			}
			for (int index = 1; index <= main.getLastRowNum(); index++) {
				Row row = main.getRow(index);
				if (blank(row)) continue;
				total++;
				String clientKey = text(row, 0);
				try {
					ScholarshipManualFullRequest request = request(row, clientKey, conditions, documents, images);
					if (!dryRun) aggregateService.create(request);
					created++;
				} catch (RuntimeException e) {
					errors.add(new ManualExcelImportResult.RowError(index + 1, clientKey, reason(e)));
				}
			}
		} catch (IOException e) {
			throw new CustomException(ErrorCode.EXCEL_PARSE_FAILED);
		}
		return new ManualExcelImportResult(dryRun, total, created, errors.size(), errors);
	}

	private ScholarshipManualFullRequest request(Row row, String key,
			Map<String, List<ScholarshipManualFullRequest.Condition>> conditions,
			Map<String, List<ScholarshipManualFullRequest.Document>> documents,
			Map<String, String> images) {
		if (!StringUtils.hasText(key) || !StringUtils.hasText(text(row, 1))) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		LocalDateTime start = date(row, 5);
		LocalDateTime end = date(row, 6);
		if (start != null && end != null && end.isBefore(start)) {
			throw new CustomException(ErrorCode.INVALID_APPLICATION_PERIOD);
		}
		return new ScholarshipManualFullRequest(
				text(row, 1), text(row, 2), text(row, 9), text(row, 10),
				enumValue(ScholarshipType.class, text(row, 3)), start, end,
				enumValue(RecruitmentStatus.class, text(row, 4)), integer(row, 8), number(row, 7),
				text(row, 11), text(row, 12), null, false, text(row, 14),
				enumValue(SubmissionChannel.class, text(row, 13)), text(row, 15), text(row, 16),
				enumValue(RequirementLevel.class, text(row, 17)), text(row, 18),
				enumValue(RequirementLevel.class, text(row, 19)), text(row, 20),
				new ScholarshipManualFullRequest.Source(text(row, 21), text(row, 22)),
				conditions.getOrDefault(key, List.of()), documents.getOrDefault(key, List.of()), images.get(key));
	}

	private Map<String, List<ScholarshipManualFullRequest.Condition>> readConditions(Workbook workbook) {
		Map<String, List<ScholarshipManualFullRequest.Condition>> result = new LinkedHashMap<>();
		Sheet sheet = workbook.getSheet(CONDITIONS);
		if (sheet == null) return result;
		for (int i = 1; i <= sheet.getLastRowNum(); i++) {
			Row row = sheet.getRow(i); if (blank(row)) continue;
			String key = required(text(row, 0));
			ScholarshipManualFullRequest.Condition value = new ScholarshipManualFullRequest.Condition(
					requiredEnum(ConditionType.class, text(row, 1)),
					enumValue(ConditionOperator.class, text(row, 3)),
					enumValue(ConditionNecessity.class, text(row, 2)),
					integer(row, 5), integer(row, 6), required(text(row, 4)), labels(text(row, 7)));
			result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
		}
		return result;
	}

	private Map<String, List<ScholarshipManualFullRequest.Document>> readDocuments(Workbook workbook) {
		Map<String, List<ScholarshipManualFullRequest.Document>> result = new LinkedHashMap<>();
		Sheet sheet = workbook.getSheet(DOCUMENTS);
		if (sheet == null) return result;
		for (int i = 1; i <= sheet.getLastRowNum(); i++) {
			Row row = sheet.getRow(i); if (blank(row)) continue;
			String key = required(text(row, 0));
			ScholarshipManualFullRequest.Document value = new ScholarshipManualFullRequest.Document(
					required(text(row, 1)), Boolean.parseBoolean(text(row, 2)), integer(row, 3), text(row, 4));
			result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
		}
		return result;
	}

	private Map<String, String> readImages(Workbook workbook) {
		Map<String, String> result = new LinkedHashMap<>();
		Sheet sheet = workbook.getSheet(IMAGES);
		if (sheet == null) return result;
		for (int i = 1; i <= sheet.getLastRowNum(); i++) {
			Row row = sheet.getRow(i); if (!blank(row)) result.put(required(text(row, 0)), text(row, 1));
		}
		return result;
	}

	private void validateFile(MultipartFile file) {
		if (file == null || file.isEmpty()) throw new CustomException(ErrorCode.EXCEL_FILE_REQUIRED);
		if (file.getSize() > MAX_BYTES) throw new CustomException(ErrorCode.EXCEL_FILE_TOO_LARGE);
		if (file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase().endsWith(".xlsx"))
			throw new CustomException(ErrorCode.EXCEL_INVALID_FORMAT);
	}

	private void writeHeader(Sheet sheet, String[] headers) {
		Row row = sheet.createRow(0);
		for (int i = 0; i < headers.length; i++) row.createCell(i).setCellValue(headers[i]);
		sheet.createFreezePane(0, 1);
	}

	private String text(Row row, int index) {
		if (row == null || row.getCell(index) == null) return null;
		Cell cell = row.getCell(index);
		String value = switch (cell.getCellType()) {
			case STRING -> cell.getStringCellValue();
			case NUMERIC -> cell.getNumericCellValue() == Math.rint(cell.getNumericCellValue())
					? Long.toString((long) cell.getNumericCellValue()) : Double.toString(cell.getNumericCellValue());
			case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
			default -> null;
		};
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private boolean blank(Row row) { return row == null || !StringUtils.hasText(text(row, 0)); }
	private String required(String value) { if (!StringUtils.hasText(value)) throw new CustomException(ErrorCode.INVALID_INPUT); return value; }
	private List<String> labels(String value) { return value == null ? List.of() : java.util.Arrays.stream(value.split(",")).map(String::trim).filter(StringUtils::hasText).toList(); }
	private Long number(Row row, int index) { String value=text(row,index); if(value==null)return null; try{return Long.parseLong(value.replace(",",""));}catch(NumberFormatException e){throw new CustomException(ErrorCode.EXCEL_INVALID_NUMBER);} }
	private Integer integer(Row row, int index) { Long value=number(row,index); return value==null?null:Math.toIntExact(value); }
	private LocalDateTime date(Row row, int index) { String value=text(row,index); if(value==null)return null; try{return LocalDateTime.parse(value,DATE);}catch(DateTimeParseException e){throw new CustomException(ErrorCode.EXCEL_INVALID_DATE);} }
	private <E extends Enum<E>> E enumValue(Class<E> type, String value) { if(value==null)return null; try{return Enum.valueOf(type,value.trim().toUpperCase());}catch(IllegalArgumentException e){throw new CustomException(ErrorCode.INVALID_INPUT);} }
	private <E extends Enum<E>> E requiredEnum(Class<E> type, String value) { E result=enumValue(type,value); if(result==null)throw new CustomException(ErrorCode.INVALID_INPUT); return result; }
	private String reason(RuntimeException e) { return e instanceof CustomException custom ? custom.getErrorCode().getMessage() : "처리 중 오류: "+e.getMessage(); }
}
