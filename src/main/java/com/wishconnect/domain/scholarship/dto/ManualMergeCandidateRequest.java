package com.wishconnect.domain.scholarship.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** LLM을 거치지 않고 관리자가 직접 만드는 중복 후보. */
public record ManualMergeCandidateRequest(
		@NotNull Long primaryScholarshipId,
		@NotNull Long duplicateScholarshipId,
		@Size(max = 1000) String reason
) {
}
