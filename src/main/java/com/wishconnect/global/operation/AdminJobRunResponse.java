package com.wishconnect.global.operation;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminJobRunResponse(
		Long id,
		String jobType,
		String trigger,
		UUID actorId,
		AdminJobStatus status,
		LocalDateTime startedAt,
		LocalDateTime finishedAt,
		String summary,
		String errorMessage
) {
	public static AdminJobRunResponse from(AdminJobRun value) {
		return new AdminJobRunResponse(value.getId(), value.getJobType(), value.getTrigger(),
				value.getActorId(), value.getStatus(), value.getStartedAt(), value.getFinishedAt(),
				value.getSummary(), value.getErrorMessage());
	}
}
