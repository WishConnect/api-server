package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.dto.ManualExcelImportResult;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualFullRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
@DisplayName("통합 수기 등록 엑셀")
class ScholarshipManualExcelServiceTest {

	@Mock private ScholarshipManualAggregateService aggregateService;
	private ScholarshipManualExcelService service;

	@BeforeEach
	void setUp() {
		service = new ScholarshipManualExcelService(aggregateService);
	}

	@Test
	@DisplayName("양식은 장학금·조건·제출서류·이미지 4개 시트를 제공한다")
	void templateHasFourSheets() throws Exception {
		try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(service.template()))) {
			assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
			assertThat(workbook.getSheet("장학금")).isNotNull();
			assertThat(workbook.getSheet("조건")).isNotNull();
			assertThat(workbook.getSheet("제출서류")).isNotNull();
			assertThat(workbook.getSheet("이미지")).isNotNull();
		}
	}

	@Test
	@DisplayName("dry-run은 4개 시트를 요청으로 조립하지만 DB 등록은 호출하지 않는다")
	void dryRun() throws Exception {
		ManualExcelImportResult result = service.importFile(workbook(), true);

		assertThat(result.totalRows()).isEqualTo(1);
		assertThat(result.createdRows()).isEqualTo(1);
		assertThat(result.errorCount()).isZero();
		verify(aggregateService, never()).create(any());
	}

	@Test
	@DisplayName("실제 반영은 임시키가 같은 조건·서류·이미지를 한 요청으로 등록한다")
	void apply() throws Exception {
		service.importFile(workbook(), false);

		ArgumentCaptor<ScholarshipManualFullRequest> captor =
				ArgumentCaptor.forClass(ScholarshipManualFullRequest.class);
		verify(aggregateService).create(captor.capture());
		ScholarshipManualFullRequest request = captor.getValue();
		assertThat(request.title()).isEqualTo("건국희망 장학");
		assertThat(request.conditions()).hasSize(1);
		assertThat(request.conditions().get(0).refLabels()).containsExactly("서울 광진구");
		assertThat(request.documents()).hasSize(1);
		assertThat(request.imageSourceUrl()).isEqualTo("https://example.com/poster.jpg");
	}

	private MockMultipartFile workbook() throws Exception {
		try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			var main = workbook.createSheet("장학금"); main.createRow(0);
			Row scholarship = main.createRow(1);
			scholarship.createCell(0).setCellValue("TEMP-001");
			scholarship.createCell(1).setCellValue("건국희망 장학");
			scholarship.createCell(2).setCellValue("건국대학교");
			scholarship.createCell(3).setCellValue("INTERNAL");
			scholarship.createCell(4).setCellValue("OPEN");
			scholarship.createCell(5).setCellValue("2026-08-19 00:00");
			scholarship.createCell(6).setCellValue("2026-09-04 23:59");

			var conditions = workbook.createSheet("조건"); conditions.createRow(0);
			Row condition = conditions.createRow(1);
			condition.createCell(0).setCellValue("TEMP-001");
			condition.createCell(1).setCellValue("REGION_RESIDENCY");
			condition.createCell(2).setCellValue("REQUIRED");
			condition.createCell(3).setCellValue("IN");
			condition.createCell(4).setCellValue("서울 거주자");
			condition.createCell(7).setCellValue("서울 광진구");

			var documents = workbook.createSheet("제출서류"); documents.createRow(0);
			Row document = documents.createRow(1);
			document.createCell(0).setCellValue("TEMP-001");
			document.createCell(1).setCellValue("신청서");
			document.createCell(2).setCellValue(false);
			document.createCell(3).setCellValue(0);

			var images = workbook.createSheet("이미지"); images.createRow(0);
			Row image = images.createRow(1);
			image.createCell(0).setCellValue("TEMP-001");
			image.createCell(1).setCellValue("https://example.com/poster.jpg");

			workbook.write(output);
			return new MockMultipartFile("file", "manual.xlsx",
					"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
		}
	}
}
