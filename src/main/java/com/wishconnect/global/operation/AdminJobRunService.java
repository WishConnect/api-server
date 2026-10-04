package com.wishconnect.global.operation;

import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminJobRunService {

	private static final int MAX_TEXT = 2000;
	/** 한 단계에서 남길 실패 상세 상한. 크레딧 소진 같은 날은 같은 원인이 수백 건 쌓이므로 자른다. */
	static final int MAX_FAILURES_PER_STEP = 300;
	private final AdminJobRunRepository repository;
	private final AdminJobFailureRepository failureRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Long start(String jobType, String trigger, UUID actorId) {
		return repository.save(AdminJobRun.builder()
				.jobType(jobType).trigger(trigger).actorId(actorId).build()).getId();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void warn(Long runId, String step, Throwable throwable) {
		warn(runId, step, throwable, AdminJobFailureType.STEP_ERROR);
	}

	/**
	 * 단계 하나가 통째로 실패했다. 실행 상태를 부분 실패로 바꾸고 단계 단위 실패 한 건을 남긴다.
	 *
	 * @param type LLM 크레딧·인증처럼 원인을 알 수 있으면 그 유형, 모르면 STEP_ERROR
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void warn(Long runId, String step, Throwable throwable, AdminJobFailureType type) {
		repository.findById(runId).ifPresent(run -> {
			run.warn(text(step + ": " + error(throwable)));
			failureRepository.save(AdminJobFailure.builder()
					.jobRunId(runId).step(step).targetType("STEP")
					.failureType(type == null ? AdminJobFailureType.STEP_ERROR : type)
					.reason(limit(error(throwable), 1000)).build());
		});
	}

	/**
	 * 단계 안의 개별 실패(출처·원문·장학금)를 남긴다. 한 건이라도 있으면 실행은 부분 실패가 된다.
	 * 상한을 넘는 나머지는 "외 n건" 한 줄로 묶는다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordFailures(Long runId, String step, List<AdminJobFailureRecord> failures) {
		if (runId == null || failures == null || failures.isEmpty()) {
			return;
		}
		repository.findById(runId).ifPresent(run -> {
			run.markPartialFailure();
			int kept = Math.min(failures.size(), MAX_FAILURES_PER_STEP);
			for (AdminJobFailureRecord failure : failures.subList(0, kept)) {
				failureRepository.save(AdminJobFailure.builder()
						.jobRunId(runId).step(limit(step, 80))
						.targetType(failure.targetType() == null ? "OTHER" : failure.targetType())
						.targetId(failure.targetId())
						.targetLabel(limit(failure.targetLabel(), 500))
						.failureType(failure.failureType() == null ? AdminJobFailureType.OTHER : failure.failureType())
						.reason(limit(redact(failure.reason()), 1000)).build());
			}
			if (failures.size() > kept) {
				failureRepository.save(AdminJobFailure.builder()
						.jobRunId(runId).step(limit(step, 80)).targetType("STEP")
						.failureType(failures.get(kept).failureType() == null
								? AdminJobFailureType.OTHER : failures.get(kept).failureType())
						.reason("외 " + (failures.size() - kept) + "건은 상한(" + MAX_FAILURES_PER_STEP
								+ ")을 넘어 기록하지 않았습니다.").build());
			}
		});
	}

	/** 한 실행의 실패 목록. 단계·유형으로 거를 수 있다. */
	@Transactional(readOnly = true)
	public Page<AdminJobFailureResponse> failures(Long runId, String step, AdminJobFailureType type,
			Pageable pageable) {
		if (!repository.existsById(runId)) {
			throw new CustomException(ErrorCode.ADMIN_JOB_RUN_NOT_FOUND);
		}
		return failureRepository.search(runId, step, type, pageable).map(AdminJobFailureResponse::from);
	}

	/**
	 * 관리자 알림함·대시보드용 요약. 최근 실행 중 부분 실패·실패만 단계별·유형별 건수와 함께 돌려준다.
	 *
	 * @param limit 최근 몇 건의 주의 대상 실행을 볼지(1~50)
	 */
	@Transactional(readOnly = true)
	public AdminJobAlertSummaryResponse alertSummary(int limit) {
		int size = Math.min(Math.max(limit, 1), 50);
		List<AdminJobRun> runs = repository.findByStatusInOrderByIdDesc(
				List.of(AdminJobStatus.WARNING, AdminJobStatus.PARTIAL_FAILURE, AdminJobStatus.FAILED),
				PageRequest.of(0, size)).getContent();
		AdminJobRun latest = repository.findFirstByOrderByIdDesc().orElse(null);

		List<Long> ids = new ArrayList<>(runs.stream().map(AdminJobRun::getId).toList());
		if (latest != null && !ids.contains(latest.getId())) {
			ids.add(latest.getId());
		}
		Map<Long, List<Object[]>> counts = ids.isEmpty() ? Map.of()
				: failureRepository.countByRunStepType(ids).stream()
						.collect(Collectors.groupingBy(row -> (Long) row[0]));

		return new AdminJobAlertSummaryResponse(
				latest == null ? null : AdminJobAlertSummaryResponse.RunSummary.of(latest,
						counts.getOrDefault(latest.getId(), List.of())),
				runs.stream().map(run -> AdminJobAlertSummaryResponse.RunSummary.of(run,
						counts.getOrDefault(run.getId(), List.of()))).toList());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void succeed(Long runId, String summary) {
		repository.findById(runId).ifPresent(run -> run.succeed(text(summary)));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fail(Long runId, Throwable throwable) {
		repository.findById(runId).ifPresent(run -> run.fail(error(throwable)));
	}

	@Transactional(readOnly = true)
	public Page<AdminJobRunResponse> find(AdminJobStatus status, Pageable pageable) {
		Page<AdminJobRun> page = status == null
				? repository.findAllByOrderByIdDesc(pageable)
				: repository.findByStatusOrderByIdDesc(status, pageable);
		return page.map(AdminJobRunResponse::from);
	}

	private String error(Throwable throwable) {
		String message = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
		return text(message.replaceAll("(?i)(password|token|secret|api[-_]?key)\\s*[=:]\\s*[^\\s,]+", "$1=[REDACTED]"));
	}

	private static String limit(String value, int max) {
		if (value == null || value.length() <= max) return value;
		return value.substring(0, max - 3) + "...";
	}

	private static String redact(String value) {
		return value == null ? null
				: value.replaceAll("(?i)(password|token|secret|api[-_]?key)\\s*[=:]\\s*[^\\s,]+", "$1=[REDACTED]");
	}

	private String text(String value) {
		if (value == null || value.length() <= MAX_TEXT) return value;
		return value.substring(0, MAX_TEXT - 3) + "...";
	}
}
