package com.wishconnect.global.operation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 알림함·대시보드용 배치 요약.
 *
 * @param latest   가장 최근 실행(상태와 무관). 대시보드 "최근 배치" 카드용
 * @param attention 최근 부분 실패·실패 실행들. 알림함용
 */
@Schema(description = "배치 부분 실패·실패 요약")
public record AdminJobAlertSummaryResponse(
		RunSummary latest,
		List<RunSummary> attention
) {

	@Schema(description = "실행 한 건의 실패 요약")
	public record RunSummary(
			Long runId,
			String jobType,
			AdminJobStatus status,
			@Schema(description = "상태 한국어 이름") String statusLabel,
			LocalDateTime startedAt,
			LocalDateTime finishedAt,
			String summary,
			@Schema(description = "단계 통째 실패 메시지(있으면)") String errorMessage,
			@Schema(description = "기록된 실패 상세 건수") long failureCount,
			@Schema(description = "단계별 실패 건수") Map<String, Long> failuresByStep,
			@Schema(description = "유형별 실패 건수") Map<AdminJobFailureType, Long> failuresByType,
			@Schema(description = "LLM 크레딧 부족이 있었는지(결제 필요)") boolean llmCreditExhausted,
			@Schema(description = "LLM 인증 오류가 있었는지(키 확인 필요)") boolean llmAuthFailed
	) {
		static RunSummary of(AdminJobRun run, List<Object[]> counts) {
			Map<String, Long> byStep = new LinkedHashMap<>();
			Map<AdminJobFailureType, Long> byType = new EnumMap<>(AdminJobFailureType.class);
			long total = 0;
			for (Object[] row : counts) {
				String step = (String) row[1];
				AdminJobFailureType type = (AdminJobFailureType) row[2];
				long count = ((Number) row[3]).longValue();
				byStep.merge(step, count, Long::sum);
				byType.merge(type, count, Long::sum);
				total += count;
			}
			return new RunSummary(run.getId(), run.getJobType(), run.getStatus(), label(run.getStatus()),
					run.getStartedAt(), run.getFinishedAt(), run.getSummary(), run.getErrorMessage(), total,
					byStep, byType, byType.containsKey(AdminJobFailureType.LLM_CREDIT),
					byType.containsKey(AdminJobFailureType.LLM_AUTH));
		}

		private static String label(AdminJobStatus status) {
			return switch (status) {
				case RUNNING -> "실행 중";
				case SUCCEEDED -> "성공";
				case WARNING, PARTIAL_FAILURE -> "부분 실패";
				case FAILED -> "실패";
			};
		}
	}
}
