package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 일괄 병합·반려 사전 검사에서 걸린 항목. 하나라도 있으면 아무것도 실행하지 않고 400(MERGE_BULK_CONFLICT)의
 * {@code data} 로 내려간다. 화면은 이 목록으로 해당 후보 카드를 강조한다.
 */
@Schema(description = "일괄 처리 사전 검사 충돌 목록")
public record MergeBulkConflictResponse(
		List<Conflict> conflicts
) {

	/** 충돌 사유 코드. */
	public enum Reason {
		/** 존재하지 않는 후보. */
		NOT_FOUND,
		/** 이미 병합·반려된 후보(승인 대기가 아님). */
		NOT_PENDING,
		/** 같은 후보 ID 를 두 번 보냄. */
		DUPLICATE_ID,
		/** (병합만) 같은 장학금이 두 후보 이상에서 "내릴 쪽". 첫 병합 뒤 둘째는 이미 내린 장학금을 다시 내리게 된다. */
		SAME_DUPLICATE,
		/** (병합만) 한 후보의 "내릴 쪽"이 다른 선택 후보의 "유지 쪽". 순서에 따라 내린 장학금으로 병합된다. */
		CHAINED_MERGE,
		/** (병합만) 유지 쪽이나 내릴 쪽이 이미 내려진(삭제된) 장학금. */
		SCHOLARSHIP_DELETED
	}

	/**
	 * @param candidateId    걸린 후보
	 * @param scholarshipIds 관련 장학금 ID(유지·내릴 쪽 중 문제가 된 것)
	 * @param reason         사유 코드
	 * @param message        화면에 그대로 보여 줄 설명
	 */
	public record Conflict(
			Long candidateId,
			List<Long> scholarshipIds,
			Reason reason,
			String message
	) {
	}
}
