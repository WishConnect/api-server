package com.wishconnect.global.operation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminJobRunTest {

	@Test
	@DisplayName("부분 실패가 있으면 완료 후에도 WARNING 상태를 유지한다")
	void warningIsPreservedOnCompletion() {
		AdminJobRun run = AdminJobRun.builder()
				.jobType("DAILY_SCHOLARSHIP_PIPELINE")
				.trigger("SCHEDULED")
				.build();

		run.warn("LLM 파싱 실패");
		run.succeed("나머지 단계 완료");

		assertThat(run.getStatus()).isEqualTo(AdminJobStatus.WARNING);
		assertThat(run.getFinishedAt()).isNotNull();
		assertThat(run.getErrorMessage()).contains("LLM 파싱 실패");
	}

	@Test
	@DisplayName("치명적 실패는 종료 시각과 오류를 기록한다")
	void failureFinishesRun() {
		AdminJobRun run = AdminJobRun.builder().jobType("SYNC").trigger("MANUAL").build();

		run.fail("외부 API 오류");

		assertThat(run.getStatus()).isEqualTo(AdminJobStatus.FAILED);
		assertThat(run.getFinishedAt()).isNotNull();
	}
}
