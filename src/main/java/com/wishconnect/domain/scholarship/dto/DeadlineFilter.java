package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "마지막 목록의 마감 형태 필터: ALL, HAS_DEADLINE, ALWAYS_OPEN")
public enum DeadlineFilter {
	ALL,
	HAS_DEADLINE,
	ALWAYS_OPEN
}
