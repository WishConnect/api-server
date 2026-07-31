package com.wishconnect.domain.common.dto;

import java.time.LocalDateTime;

/**
 * 학교·전공 동기화 진행 상태. 동기화가 오래 걸려 즉시 202 로 반환하므로,
 * 호출자는 이 응답으로 진행 여부와 결과를 확인한다.
 *
 * @param state      NEVER_RUN / RUNNING / SUCCEEDED / FAILED
 * @param startedAt  마지막 실행 시작 시각
 * @param finishedAt 마지막 실행 종료 시각(실행 중이면 null)
 * @param result     성공 시 집계 결과(그 외 null)
 * @param message    실패 시 원인 또는 안내 메시지
 */
public record AcademicInfoSyncStatusResponse(
		State state,
		LocalDateTime startedAt,
		LocalDateTime finishedAt,
		AcademicInfoSyncResponse result,
		String message
) {

	public enum State {
		NEVER_RUN, RUNNING, SUCCEEDED, FAILED
	}

	public static AcademicInfoSyncStatusResponse neverRun() {
		return new AcademicInfoSyncStatusResponse(State.NEVER_RUN, null, null, null, null);
	}

	public static AcademicInfoSyncStatusResponse running(LocalDateTime startedAt, String message) {
		return new AcademicInfoSyncStatusResponse(State.RUNNING, startedAt, null, null, message);
	}

	public static AcademicInfoSyncStatusResponse succeeded(
			LocalDateTime startedAt, AcademicInfoSyncResponse result) {
		return new AcademicInfoSyncStatusResponse(
				State.SUCCEEDED, startedAt, LocalDateTime.now(), result, null);
	}

	public static AcademicInfoSyncStatusResponse failed(LocalDateTime startedAt, String message) {
		return new AcademicInfoSyncStatusResponse(
				State.FAILED, startedAt, LocalDateTime.now(), null, message);
	}
}
