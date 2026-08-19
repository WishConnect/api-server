package com.wishconnect.global.operation;

import java.time.LocalDateTime;
import java.util.List;

public record AdminLogResponse(
		LocalDateTime checkedAt,
		boolean available,
		String message,
		List<String> lines
) {
}
