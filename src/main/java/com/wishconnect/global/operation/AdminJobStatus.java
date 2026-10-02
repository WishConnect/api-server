package com.wishconnect.global.operation;

public enum AdminJobStatus {
	RUNNING,
	SUCCEEDED,
	/**
	 * 예전 "일부 단계 실패" 표시. 새 실행은 {@link #PARTIAL_FAILURE} 를 쓴다.
	 * 이미 쌓인 이력 행 때문에 값은 남겨 둔다(DB CHECK 와 함께 유지).
	 */
	WARNING,
	/** 끝까지 돌았지만 일부 단계 또는 일부 출처·원문·장학금이 실패했다. 실패 상세는 admin_job_failure. */
	PARTIAL_FAILURE,
	FAILED;

	/** 관리자 알림 대상(부분 실패·실패). */
	public boolean needsAttention() {
		return this == WARNING || this == PARTIAL_FAILURE || this == FAILED;
	}
}
