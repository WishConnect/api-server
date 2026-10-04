package com.wishconnect.domain.scholarship.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.wishconnect.domain.scholarship.dto.CollectResultResponse;
import com.wishconnect.domain.scholarship.dto.NoticeParsingResponse;
import com.wishconnect.global.operation.AdminJobFailureRecord;
import com.wishconnect.global.operation.AdminJobFailureType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("일일 배치 — 단계 결과를 실패 상세로 바꾸기")
class ScholarshipSyncSchedulerFailureTest {

	@Test
	@DisplayName("파싱 결과에서 FAILED 원문만 실패로 옮기고 유형을 보존한다")
	void mapsParseFailures() {
		NoticeParsingResponse response = new NoticeParsingResponse(3, 1, 1, 1, false, List.of(
				new NoticeParsingResponse.Item(1L, "UNIV_A", "u1", "PARSED", "a", null, null, 0, 0, false, "ok"),
				new NoticeParsingResponse.Item(2L, "UNIV_A", "u2", "SKIPPED", null, null, null, 0, 0, false, "본문 없음"),
				new NoticeParsingResponse.Item(3L, "UNIV_B", "u3", "FAILED", null, null, null, 0, 0, false,
						"[크레딧 부족] LLM 호출 실패", "LLM_CREDIT")));

		List<AdminJobFailureRecord> failures = ScholarshipSyncScheduler.parseFailures(response);

		assertThat(failures).singleElement().satisfies(failure -> {
			assertThat(failure.targetType()).isEqualTo("RAW_SCHOLARSHIP");
			assertThat(failure.targetId()).isEqualTo(3L);
			assertThat(failure.targetLabel()).isEqualTo("UNIV_B u3");
			assertThat(failure.failureType()).isEqualTo(AdminJobFailureType.LLM_CREDIT);
		});
	}

	@Test
	@DisplayName("수집 0건은 실패가 아니고, 출처 예외만 실패다")
	void mapsCollectFailures() {
		List<AdminJobFailureRecord> failures = ScholarshipSyncScheduler.collectFailures(List.of(
				new CollectResultResponse("UNIV_A", 0, 0, 0),
				new CollectResultResponse("UNIV_SOGANG", 0, 0, 0, "SSLHandshakeException: PKIX path building failed")));

		assertThat(failures).singleElement().satisfies(failure -> {
			assertThat(failure.targetLabel()).isEqualTo("UNIV_SOGANG");
			assertThat(failure.failureType()).isEqualTo(AdminJobFailureType.COLLECT);
		});
	}
}
