package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.dto.ExcelImportResult;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualRequest;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScholarshipExcelServiceTest {

	@Mock
	private ScholarshipRepository scholarshipRepository;
	@Mock
	private ScholarshipManualService scholarshipManualService;

	@InjectMocks
	private ScholarshipExcelService service;

	private Scholarship scholarship(Long id) {
		Scholarship s = Scholarship.builder()
				.title("미래인재 장학금")
				.provider("위시커넥트")
				.summary("요약")
				.amount(1_000_000L)
				.selectionCount(10)
				.homepageUrl("https://example.com")
				.scholarshipType(ScholarshipType.EXTERNAL)
				.recruitmentStatus(RecruitmentStatus.OPEN)
				.primarySource("KOSAF_SCHOLARSHIP")
				.applicationStartAt(LocalDateTime.of(2026, 8, 1, 0, 0))
				.applicationEndAt(LocalDateTime.of(2026, 8, 31, 23, 59))
				.build();
		ReflectionTestUtils.setField(s, "id", id);
		return s;
	}

	/** 내보내기 양식 그대로 한 행짜리 파일을 만든다. cells 는 편집열(제목~URL)만 채운다. */
	private MockMultipartFile xlsx(Object id, Object... cells) throws Exception {
		try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = wb.createSheet("장학금");
			sheet.createRow(0).createCell(0).setCellValue("ID(수정금지)");
			Row row = sheet.createRow(1);
			if (id instanceof Number n) {
				row.createCell(0).setCellValue(n.doubleValue());
			} else if (id != null) {
				row.createCell(0).setCellValue(String.valueOf(id));
			}
			for (int i = 0; i < cells.length; i++) {
				if (cells[i] == null) {
					continue;
				}
				int col = 3 + i;
				if (cells[i] instanceof Number n) {
					row.createCell(col).setCellValue(n.doubleValue());
				} else {
					row.createCell(col).setCellValue(String.valueOf(cells[i]));
				}
			}
			wb.write(out);
			return new MockMultipartFile("file", "edit.xlsx",
					"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
					new ByteArrayInputStream(out.toByteArray()).readAllBytes());
		}
	}

	@Test
	@DisplayName("내보내기 파일에는 ID 가 채워져 있어 사람이 입력할 필요가 없다")
	void exportFillsIdColumn() throws Exception {
		given(scholarshipRepository.findAllForExcelExport())
				.willReturn(List.of(scholarship(101L), scholarship(102L)));

		byte[] bytes = service.export();

		try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			Sheet sheet = wb.getSheetAt(0);
			assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("ID");
			assertThat((long) sheet.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(101L);
			assertThat((long) sheet.getRow(2).getCell(0).getNumericCellValue()).isEqualTo(102L);
			assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("미래인재 장학금");
		}
	}

	@Test
	@DisplayName("dryRun 이면 DB 를 건드리지 않는다")
	void dryRunDoesNotWrite() throws Exception {
		given(scholarshipRepository.existsById(101L)).willReturn(true);

		ExcelImportResult result = service.importFrom(xlsx(101L, "수정된 제목"), true);

		assertThat(result.dryRun()).isTrue();
		assertThat(result.appliedRows()).isEqualTo(1);
		assertThat(result.errorCount()).isZero();
		verify(scholarshipManualService, never()).update(anyLong(), any());
	}

	@Test
	@DisplayName("dryRun=false 면 실제로 반영한다")
	void appliesWhenNotDryRun() throws Exception {
		ExcelImportResult result = service.importFrom(xlsx(101L, "수정된 제목"), false);

		assertThat(result.appliedRows()).isEqualTo(1);
		ArgumentCaptor<ScholarshipManualRequest> captor =
				ArgumentCaptor.forClass(ScholarshipManualRequest.class);
		verify(scholarshipManualService).update(anyLong(), captor.capture());
		assertThat(captor.getValue().title()).isEqualTo("수정된 제목");
	}

	/** 빈 칸을 null 로 덮어쓰면 안 건드린 칸의 멀쩡한 데이터가 날아간다. */
	@Test
	@DisplayName("빈 칸은 변경하지 않는다")
	void blankCellsAreNotOverwritten() throws Exception {
		service.importFrom(xlsx(101L, "제목만 수정"), false);

		ArgumentCaptor<ScholarshipManualRequest> captor =
				ArgumentCaptor.forClass(ScholarshipManualRequest.class);
		verify(scholarshipManualService).update(anyLong(), captor.capture());
		ScholarshipManualRequest request = captor.getValue();
		assertThat(request.title()).isEqualTo("제목만 수정");
		assertThat(request.provider()).isNull();
		assertThat(request.summary()).isNull();
		assertThat(request.amount()).isNull();
		assertThat(request.homepageUrl()).isNull();
	}

	@Test
	@DisplayName("아무것도 수정하지 않은 행은 반영 건수에 넣지 않는다")
	void untouchedRowIsNotCounted() throws Exception {
		ExcelImportResult result = service.importFrom(xlsx(101L), false);

		assertThat(result.appliedRows()).isZero();
		verify(scholarshipManualService, never()).update(anyLong(), any());
	}

	@Test
	@DisplayName("ID 가 빈 행은 오류로 보고하고 나머지는 계속 처리한다")
	void rowWithoutIdIsReportedAsError() throws Exception {
		ExcelImportResult result = service.importFrom(xlsx(null, "새로 추가한 행"), false);

		assertThat(result.errorCount()).isEqualTo(1);
		assertThat(result.errors().get(0).reason())
				.isEqualTo(ErrorCode.EXCEL_ROW_ID_REQUIRED.getMessage());
		// 엑셀 화면의 2행(헤더 다음 줄)으로 보고해야 사람이 찾아갈 수 있다.
		assertThat(result.errors().get(0).rowNumber()).isEqualTo(2);
	}

	@Test
	@DisplayName("숫자 칸에 글자가 있으면 그 행만 오류로 남긴다")
	void invalidNumberIsRowLevelError() throws Exception {
		// 편집열 4번째가 지원금액
		ExcelImportResult result = service.importFrom(xlsx(101L, null, null, null, "백만원"), false);

		assertThat(result.errorCount()).isEqualTo(1);
		assertThat(result.errors().get(0).reason())
				.isEqualTo(ErrorCode.EXCEL_INVALID_NUMBER.getMessage());
	}

	@Test
	@DisplayName("금액에 콤마를 넣어도 읽는다")
	void amountAcceptsThousandSeparator() throws Exception {
		service.importFrom(xlsx(101L, null, null, null, "1,500,000"), false);

		ArgumentCaptor<ScholarshipManualRequest> captor =
				ArgumentCaptor.forClass(ScholarshipManualRequest.class);
		verify(scholarshipManualService).update(anyLong(), captor.capture());
		assertThat(captor.getValue().amount()).isEqualTo(1_500_000L);
	}

	@Test
	@DisplayName("xlsx 가 아니면 거부한다")
	void rejectsNonXlsx() {
		MockMultipartFile csv = new MockMultipartFile("file", "edit.csv", "text/csv", "a,b".getBytes());

		assertThatThrownBy(() -> service.importFrom(csv, true))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXCEL_INVALID_FORMAT);
	}

	@Test
	@DisplayName("빈 파일은 거부한다")
	void rejectsEmptyFile() {
		MockMultipartFile empty = new MockMultipartFile("file", "edit.xlsx", null, new byte[0]);

		assertThatThrownBy(() -> service.importFrom(empty, true))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXCEL_FILE_REQUIRED);
	}

	/** POI 가 파일을 메모리에 올린다. t3.small(-Xmx1g)에 OOM 이력이 있어 상한이 필요하다. */
	@Test
	@DisplayName("1MB 를 넘는 파일은 거부한다")
	void rejectsOversizedFile() {
		MockMultipartFile big = new MockMultipartFile("file", "edit.xlsx", null,
				new byte[(int) ScholarshipExcelService.MAX_FILE_BYTES + 1]);

		assertThatThrownBy(() -> service.importFrom(big, true))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXCEL_FILE_TOO_LARGE);
	}

	@Test
	@DisplayName("존재하지 않는 ID 는 dryRun 단계에서 걸러진다")
	void missingIdIsCaughtInDryRun() throws Exception {
		given(scholarshipRepository.existsById(999L)).willReturn(false);

		ExcelImportResult result = service.importFrom(xlsx(999L, "수정"), true);

		assertThat(result.errorCount()).isEqualTo(1);
		assertThat(result.errors().get(0).reason())
				.isEqualTo(ErrorCode.SCHOLARSHIP_NOT_FOUND.getMessage());
	}
}
