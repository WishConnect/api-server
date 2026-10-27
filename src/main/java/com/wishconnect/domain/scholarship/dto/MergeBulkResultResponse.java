package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 중복 후보 일괄 병합·반려 결과. 건마다 별도 트랜잭션으로 처리하므로 일부만 성공할 수 있다.
 *
 * @param total     요청한 후보 수
 * @param succeeded 병합(MERGED) 또는 반려(REJECTED)된 수
 * @param skipped   실행 중 다른 관리자가 먼저 처리하는 등 상태가 바뀌어 건너뛴 수
 * @param failed    처리 중 오류가 난 수(그 건은 아무것도 바뀌지 않고 승인 대기로 남는다)
 * @param results   요청 순서대로 건별 결과
 */
@Schema(description = "중복 후보 일괄 처리 결과")
public record MergeBulkResultResponse(
		int total,
		int succeeded,
		int skipped,
		int failed,
		List<Item> results
) {

	public enum Result { MERGED, REJECTED, SKIPPED, FAILED }

	/**
	 * @param errorCode SKIPPED·FAILED 일 때 원인 코드(ErrorCode 이름). 성공이면 null
	 * @param message   화면에 그대로 보여 줄 한 줄 설명
	 */
	public record Item(
			Long candidateId,
			Long primaryId,
			Long duplicateId,
			Result result,
			String errorCode,
			String message
	) {
	}

	public static MergeBulkResultResponse of(List<Item> results) {
		int succeeded = 0;
		int skipped = 0;
		int failed = 0;
		for (Item item : results) {
			switch (item.result()) {
				case MERGED, REJECTED -> succeeded++;
				case SKIPPED -> skipped++;
				case FAILED -> failed++;
			}
		}
		return new MergeBulkResultResponse(results.size(), succeeded, skipped, failed, List.copyOf(results));
	}
}
