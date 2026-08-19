package com.wishconnect.global.operation;

import java.time.LocalDateTime;

public record AdminSystemStatusResponse(
		LocalDateTime checkedAt,
		Check application,
		Check database,
		Check redis,
		Memory jvmHeap,
		Disk disk
) {
	public record Check(String status, long latencyMs, String detail) {
	}
	public record Memory(long usedBytes, long maxBytes, int usedPercent) {
	}
	public record Disk(long usedBytes, long totalBytes, int usedPercent, String path) {
	}
}
