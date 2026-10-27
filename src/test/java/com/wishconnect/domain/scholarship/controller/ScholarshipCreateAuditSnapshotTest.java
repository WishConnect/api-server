package com.wishconnect.domain.scholarship.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.dto.AdminScholarshipDetailResponse;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipEditSnapshot;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualFullRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualFullResponse;
import com.wishconnect.domain.scholarship.service.ScholarshipAdminOverviewService;
import com.wishconnect.domain.scholarship.service.ScholarshipManualAggregateService;
import com.wishconnect.global.audit.AdminAction;
import com.wishconnect.global.audit.AdminAuditLogService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 장학금 등록 기록에 등록 직후 내용이 남는지(작업 기록의 "등록 내용" 화면용).
 * before 는 비워 둔다 — 복구 대상이 되지 않게(복구는 before 가 있는 수정·내리기 기록만).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScholarshipCreateAuditSnapshotTest {

	@Mock private ScholarshipManualAggregateService aggregateService;
	@Mock private ScholarshipAdminOverviewService overviewService;
	@Mock private AdminAuditLogService auditLogService;
	@InjectMocks private ScholarshipAdminController controller;

	private final String actor = UUID.randomUUID().toString();

	@Test
	@DisplayName("통합 수기 등록은 SCHOLARSHIP_CREATE 기록에 등록 직후 스냅샷(after)을 남기고 before 는 null")
	void fullCreateSavesAfterSnapshot() {
		given(aggregateService.create(any())).willReturn(new ScholarshipManualFullResponse(41L, 9L, 2, 0, 1, false));
		AdminScholarshipDetailResponse created = detail("새 장학금");
		given(overviewService.detail(41L)).willReturn(created);

		controller.createManualFull(actor, mock(ScholarshipManualFullRequest.class));

		ArgumentCaptor<Object> after = ArgumentCaptor.forClass(Object.class);
		verify(auditLogService).recordChange(eq(UUID.fromString(actor)), eq(AdminAction.SCHOLARSHIP_CREATE),
				eq("SCHOLARSHIP"), eq(41L), anyString(), isNull(), after.capture());
		assertThat(after.getValue()).isInstanceOf(AdminScholarshipEditSnapshot.class);
		assertThat(((AdminScholarshipEditSnapshot) after.getValue()).scholarship().title()).isEqualTo("새 장학금");
	}

	@Test
	@DisplayName("원문 수기 정제도 등록 직후 스냅샷을 남긴다")
	void refineSavesAfterSnapshot() {
		given(aggregateService.createFromRaw(anyLong(), any()))
				.willReturn(new ScholarshipManualFullResponse(42L, 8766L, 0, 0, 0, false));
		AdminScholarshipDetailResponse refined = detail("정제한 장학금");
		given(overviewService.detail(42L)).willReturn(refined);

		controller.createManualFromRaw(actor, 8766L, mock(ScholarshipManualFullRequest.class));

		verify(auditLogService).recordChange(eq(UUID.fromString(actor)), eq(AdminAction.SCHOLARSHIP_CREATE),
				eq("SCHOLARSHIP"), eq(42L), eq("원본 #8766 수기 정제"), isNull(), any(AdminScholarshipEditSnapshot.class));
	}

	@Test
	@DisplayName("스냅샷 조회가 실패해도 등록 응답과 감사 기록(스냅샷 없이)은 그대로 남는다")
	void snapshotFailureDoesNotBreakCreate() {
		given(aggregateService.create(any())).willReturn(new ScholarshipManualFullResponse(43L, 10L, 0, 0, 0, false));
		given(overviewService.detail(43L)).willThrow(new IllegalStateException("조회 실패"));

		var response = controller.createManualFull(actor, mock(ScholarshipManualFullRequest.class));

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		verify(auditLogService).recordChange(eq(UUID.fromString(actor)), eq(AdminAction.SCHOLARSHIP_CREATE),
				eq("SCHOLARSHIP"), eq(43L), anyString(), isNull(), isNull());
	}

	private static AdminScholarshipDetailResponse detail(String title) {
		AdminScholarshipDetailResponse.ScholarshipData data = mock(AdminScholarshipDetailResponse.ScholarshipData.class);
		given(data.title()).willReturn(title);
		AdminScholarshipDetailResponse detail = mock(AdminScholarshipDetailResponse.class);
		given(detail.scholarship()).willReturn(data);
		given(detail.conditions()).willReturn(List.of());
		given(detail.documents()).willReturn(List.of());
		given(detail.timeline()).willReturn(List.of());
		return detail;
	}
}
