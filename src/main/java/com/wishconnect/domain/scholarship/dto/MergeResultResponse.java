package com.wishconnect.domain.scholarship.dto;

import java.util.Map;

/**
 * 병합 승인 처리 결과.
 *
 * @param candidateId  처리한 후보 ID
 * @param status       처리 후 상태 (MERGED / REJECTED / FAILED)
 * @param primaryId    남은 장학금
 * @param duplicateId  소프트 삭제된 장학금
 * @param moved        테이블별 재지정·삭제 건수. 무엇이 옮겨졌는지 확인용
 */
public record MergeResultResponse(
		Long candidateId,
		String status,
		Long primaryId,
		Long duplicateId,
		Map<String, Integer> moved
) {
}
