package com.wishconnect.global.operation;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminJobRunService {

	private static final int MAX_TEXT = 2000;
	private final AdminJobRunRepository repository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Long start(String jobType, String trigger, UUID actorId) {
		return repository.save(AdminJobRun.builder()
				.jobType(jobType).trigger(trigger).actorId(actorId).build()).getId();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void warn(Long runId, String step, Throwable throwable) {
		repository.findById(runId).ifPresent(run -> run.warn(text(step + ": " + error(throwable))));
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

	private String text(String value) {
		if (value == null || value.length() <= MAX_TEXT) return value;
		return value.substring(0, MAX_TEXT - 3) + "...";
	}
}
