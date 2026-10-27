package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 원본 만료 처리 결과. 건마다 따로 처리하므로 일부만 성공할 수 있다.
 *
 * @param scholarshipClosed 이번 요청으로 마감(CLOSED) 처리한 연결 장학금 수
 * @param warnings          연결 장학금이 아직 모집 중(OPEN/UPCOMING, 마감 전)이었던 건수. 서버는 막지 않는다
 */
@Schema(description = "원본 만료 처리 결과")
public record RawDismissResultResponse(
		int total,
		int dismissed,
		int skipped,
		int failed,
		int scholarshipClosed,
		int warnings,
		List<Item> results
) {

	public enum Result { DISMISSED, SKIPPED, FAILED }

	/**
	 * @param linkedScholarshipId     연결된 장학금(없으면 null)
	 * @param scholarshipClosed       이번에 그 장학금을 마감했는지
	 * @param warningOpenScholarship  연결 장학금이 OPEN/UPCOMING 이고 모집 마감이 아직 안 지났는지(처리 전 기준)
	 */
	public record Item(
			Long rawId,
			Result result,
			Long linkedScholarshipId,
			boolean scholarshipClosed,
			boolean warningOpenScholarship,
			String message
	) {
	}

	public static RawDismissResultResponse of(List<Item> results) {
		int dismissed = 0;
		int skipped = 0;
		int failed = 0;
		int closed = 0;
		int warnings = 0;
		for (Item item : results) {
			switch (item.result()) {
				case DISMISSED -> dismissed++;
				case SKIPPED -> skipped++;
				case FAILED -> failed++;
			}
			if (item.scholarshipClosed()) closed++;
			if (item.warningOpenScholarship()) warnings++;
		}
		return new RawDismissResultResponse(results.size(), dismissed, skipped, failed, closed, warnings,
				List.copyOf(results));
	}
}
