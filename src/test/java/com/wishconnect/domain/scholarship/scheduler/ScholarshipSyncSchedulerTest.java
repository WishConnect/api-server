package com.wishconnect.domain.scholarship.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.dto.ConditionExtractionResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipSyncResponse;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.service.ConditionExtractionService;
import com.wishconnect.domain.scholarship.service.ScholarshipSyncService;
import com.wishconnect.global.operation.AdminJobRunService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScholarshipSyncSchedulerTest {

	@Mock
	private ScholarshipSyncService scholarshipSyncService;

	@Mock
	private ConditionExtractionService conditionExtractionService;

	@Mock(strictness = org.mockito.Mock.Strictness.LENIENT)
	private ScholarshipRepository scholarshipRepository;

	@Mock
	private AdminJobRunService adminJobRunService;

	@InjectMocks
	private ScholarshipSyncScheduler scholarshipSyncScheduler;

	@Test
	@DisplayName("배치 실행 시 동기화 후 조건 추출까지 호출한다")
	void runsSyncThenExtraction() {
		given(adminJobRunService.start("DAILY_SCHOLARSHIP_PIPELINE", "SCHEDULED", null)).willReturn(1L);
		given(scholarshipSyncService.sync())
				.willReturn(new ScholarshipSyncResponse(10, 10, 0, List.of()));
		given(conditionExtractionService.extract())
				.willReturn(new ConditionExtractionResponse(5, 4, 1));

		scholarshipSyncScheduler.syncDaily();

		verify(scholarshipSyncService).sync();
		verify(conditionExtractionService).extract();
		verify(adminJobRunService).succeed(1L, "장학금 일일 파이프라인 완료");
	}

	@Test
	@DisplayName("동기화가 실패하면 조건 추출은 시도하지 않는다")
	void skipsExtractionWhenSyncFails() {
		given(adminJobRunService.start("DAILY_SCHOLARSHIP_PIPELINE", "SCHEDULED", null)).willReturn(2L);
		given(scholarshipSyncService.sync()).willThrow(new RuntimeException("외부 API 오류"));

		scholarshipSyncScheduler.syncDaily();

		verify(scholarshipSyncService).sync();
		org.mockito.Mockito.verifyNoInteractions(conditionExtractionService);
		verify(adminJobRunService).fail(org.mockito.ArgumentMatchers.eq(2L),
				org.mockito.ArgumentMatchers.any(RuntimeException.class));
	}

	@Test
	@DisplayName("조건 추출 실패는 배치 전체를 실패시키지 않는다")
	void extractionFailureDoesNotPropagate() {
		given(adminJobRunService.start("DAILY_SCHOLARSHIP_PIPELINE", "SCHEDULED", null)).willReturn(3L);
		given(scholarshipSyncService.sync())
				.willReturn(new ScholarshipSyncResponse(10, 10, 0, List.of()));
		given(conditionExtractionService.extract()).willThrow(new RuntimeException("LLM 키 없음"));

		scholarshipSyncScheduler.syncDaily();

		verify(conditionExtractionService).extract();
		verify(adminJobRunService).warn(org.mockito.ArgumentMatchers.eq(3L),
				org.mockito.ArgumentMatchers.eq("조건 추출"),
				org.mockito.ArgumentMatchers.any(RuntimeException.class));
	}
}
