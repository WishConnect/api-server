package com.wishconnect.domain.scholarship.dto;

/**
 * 중복 후보 탐지 배치 결과.
 *
 * @param scannedCount   검사한 장학금 수
 * @param groupCount     blocking 으로 묶인 후보 그룹 수 (= LLM 호출 횟수)
 * @param candidateCount 새로 큐에 올린 후보 쌍 수
 * @param skippedCount   이미 후보로 올라와 있어 건너뛴 쌍 수
 * @param failedCount    LLM 호출·응답 파싱 실패 그룹 수
 */
public record MergeDetectionResponse(
		int scannedCount,
		int groupCount,
		int candidateCount,
		int skippedCount,
		int failedCount
) {
}
