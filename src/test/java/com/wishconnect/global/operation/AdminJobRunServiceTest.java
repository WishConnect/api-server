package com.wishconnect.global.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("배치 실행 이력 — 부분 실패와 실패 상세")
class AdminJobRunServiceTest {

	@Mock private AdminJobRunRepository repository;
	@Mock private AdminJobFailureRepository failureRepository;
	private AdminJobRunService service;
	private AdminJobRun run;

	@BeforeEach
	void setUp() {
		service = new AdminJobRunService(repository, failureRepository);
		run = AdminJobRun.builder().jobType("DAILY_SCHOLARSHIP_PIPELINE").trigger("SCHEDULED").build();
		ReflectionTestUtils.setField(run, "id", 7L);
		given(repository.findById(7L)).willReturn(Optional.of(run));
	}

	private static AdminJobFailureRecord failure(long rawId, AdminJobFailureType type) {
		return new AdminJobFailureRecord("RAW_SCHOLARSHIP", rawId, "공고 " + rawId, type, "이유");
	}

	@Test
	@DisplayName("일부 원문이 실패하면 끝나도 SUCCEEDED 가 아니라 PARTIAL_FAILURE 다")
	void partialFailureSurvivesSucceed() {
		service.recordFailures(7L, "대학 공지 LLM 파싱", List.of(failure(1, AdminJobFailureType.LLM_CREDIT)));
		service.succeed(7L, "완료");

		assertThat(run.getStatus()).isEqualTo(AdminJobStatus.PARTIAL_FAILURE);
		assertThat(run.getFinishedAt()).isNotNull();
		ArgumentCaptor<AdminJobFailure> saved = ArgumentCaptor.forClass(AdminJobFailure.class);
		verify(failureRepository).save(saved.capture());
		assertThat(saved.getValue().getFailureType()).isEqualTo(AdminJobFailureType.LLM_CREDIT);
		assertThat(saved.getValue().getTargetId()).isEqualTo(1L);
		assertThat(saved.getValue().getStep()).isEqualTo("대학 공지 LLM 파싱");
	}

	@Test
	@DisplayName("실패가 없으면 기록도 상태 변경도 없다")
	void noFailuresNoChange() {
		service.recordFailures(7L, "대학 공지 수집", List.of());
		service.succeed(7L, "완료");

		assertThat(run.getStatus()).isEqualTo(AdminJobStatus.SUCCEEDED);
		verify(failureRepository, never()).save(any());
	}

	@Test
	@DisplayName("단계 통째 실패는 부분 실패로 바꾸고 STEP 실패 한 건을 남긴다")
	void stepWarningRecordsStepFailure() {
		service.warn(7L, "조건 추출", new IllegalStateException("boom"), AdminJobFailureType.LLM_AUTH);

		assertThat(run.getStatus()).isEqualTo(AdminJobStatus.PARTIAL_FAILURE);
		ArgumentCaptor<AdminJobFailure> saved = ArgumentCaptor.forClass(AdminJobFailure.class);
		verify(failureRepository).save(saved.capture());
		assertThat(saved.getValue().getTargetType()).isEqualTo("STEP");
		assertThat(saved.getValue().getFailureType()).isEqualTo(AdminJobFailureType.LLM_AUTH);
	}

	@Test
	@DisplayName("실패한 실행은 상세가 붙어도 FAILED 로 남는다")
	void failedStaysFailed() {
		service.fail(7L, new IllegalStateException("sync down"));
		service.recordFailures(7L, "공공데이터 동기화", List.of(failure(1, AdminJobFailureType.STEP_ERROR)));

		assertThat(run.getStatus()).isEqualTo(AdminJobStatus.FAILED);
	}

	@Test
	@DisplayName("한 단계 실패가 상한을 넘으면 나머지는 '외 n건' 한 줄로 묶는다")
	void capsFailuresPerStep() {
		List<AdminJobFailureRecord> many = new ArrayList<>();
		for (int i = 0; i < AdminJobRunService.MAX_FAILURES_PER_STEP + 5; i++) {
			many.add(failure(i, AdminJobFailureType.LLM_CREDIT));
		}

		service.recordFailures(7L, "대학 공지 LLM 파싱", many);

		verify(failureRepository, times(AdminJobRunService.MAX_FAILURES_PER_STEP + 1)).save(any());
	}

	@Test
	@DisplayName("요약은 단계별·유형별 건수와 크레딧 부족 표시를 준다")
	void alertSummaryAggregates() {
		run.markPartialFailure();
		given(repository.findByStatusInOrderByIdDesc(anyCollection(), any())).willReturn(new PageImpl<>(List.of(run)));
		given(repository.findFirstByOrderByIdDesc()).willReturn(Optional.of(run));
		given(failureRepository.countByRunStepType(anyCollection())).willReturn(List.of(
				new Object[] {7L, "대학 공지 LLM 파싱", AdminJobFailureType.LLM_CREDIT, 12L},
				new Object[] {7L, "대학 공지 수집", AdminJobFailureType.COLLECT, 1L}));

		AdminJobAlertSummaryResponse summary = service.alertSummary(10);

		assertThat(summary.attention()).hasSize(1);
		AdminJobAlertSummaryResponse.RunSummary latest = summary.latest();
		assertThat(latest.failureCount()).isEqualTo(13);
		assertThat(latest.failuresByStep()).containsEntry("대학 공지 LLM 파싱", 12L);
		assertThat(latest.failuresByType()).containsEntry(AdminJobFailureType.COLLECT, 1L);
		assertThat(latest.llmCreditExhausted()).isTrue();
		assertThat(latest.llmAuthFailed()).isFalse();
		assertThat(latest.statusLabel()).isEqualTo("부분 실패");
	}
}
