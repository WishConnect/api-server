package com.wishconnect.domain.scholarship.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.dto.ConditionExtractionResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipSyncResponse;
import com.wishconnect.domain.scholarship.service.ConditionExtractionService;
import com.wishconnect.domain.scholarship.service.ScholarshipSyncService;
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

	@InjectMocks
	private ScholarshipSyncScheduler scholarshipSyncScheduler;

	@Test
	@DisplayName("배치 실행 시 동기화 후 조건 추출까지 호출한다")
	void runsSyncThenExtraction() {
		given(scholarshipSyncService.sync())
				.willReturn(new ScholarshipSyncResponse(10, 10, 0, List.of()));
		given(conditionExtractionService.extract())
				.willReturn(new ConditionExtractionResponse(5, 4, 1));

		scholarshipSyncScheduler.syncDaily();

		verify(scholarshipSyncService).sync();
		verify(conditionExtractionService).extract();
	}

	@Test
	@DisplayName("동기화가 실패하면 조건 추출은 시도하지 않는다")
	void skipsExtractionWhenSyncFails() {
		given(scholarshipSyncService.sync()).willThrow(new RuntimeException("외부 API 오류"));

		scholarshipSyncScheduler.syncDaily();

		verify(scholarshipSyncService).sync();
		org.mockito.Mockito.verifyNoInteractions(conditionExtractionService);
	}

	@Test
	@DisplayName("조건 추출 실패는 배치 전체를 실패시키지 않는다")
	void extractionFailureDoesNotPropagate() {
		given(scholarshipSyncService.sync())
				.willReturn(new ScholarshipSyncResponse(10, 10, 0, List.of()));
		given(conditionExtractionService.extract()).willThrow(new RuntimeException("LLM 키 없음"));

		scholarshipSyncScheduler.syncDaily();

		verify(conditionExtractionService).extract();
	}
}
