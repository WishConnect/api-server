package com.wishconnect.domain.scholarship.dto;

import java.util.List;

/**
 * 중복 후보 탐지 배치 결과.
 *
 * @param scannedCount   검사한 장학금 수
 * @param groupCount     blocking 으로 묶인 후보 그룹 수 (= LLM 호출 횟수)
 * @param candidateCount 새로 큐에 올린 후보 쌍 수
 * @param skippedCount   이미 후보로 올라와 있어 건너뛴 쌍 수
 * @param failedCount    LLM 호출·응답 파싱 실패 그룹 수
 * @param failures       실패한 묶음별 원인(배치 실패 상세 기록용)
 */
public record MergeDetectionResponse(
		int scannedCount,
		int groupCount,
		int candidateCount,
		int skippedCount,
		int failedCount,
		List<GroupFailure> failures
) {

	public MergeDetectionResponse(int scannedCount, int groupCount, int candidateCount, int skippedCount,
			int failedCount) {
		this(scannedCount, groupCount, candidateCount, skippedCount, failedCount, List.of());
	}

	/** @param failureType AdminJobFailureType 이름 */
	public record GroupFailure(String groupKey, String failureType, String reason) {
	}
}
