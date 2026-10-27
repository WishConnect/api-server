package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 원본 만료 처리 되돌리기 결과.
 *
 * @param notice 화면에 그대로 보여 줄 안내. 연결 장학금의 모집 상태는 되돌리지 않는다는 내용
 */
@Schema(description = "원본 만료 처리 되돌리기 결과")
public record RawRestoreResultResponse(
		int total,
		int restored,
		int skipped,
		int failed,
		String notice,
		List<Item> results
) {

	public static final String NOTICE = "원본의 만료 처리만 되돌렸습니다. 연결된 장학금의 모집 상태는 그대로입니다"
			+ "(만료 처리 때 마감했다면 통합 수정이나 작업 기록의 복구로 되돌리세요).";

	public enum Result { RESTORED, SKIPPED, FAILED }

	/** @param linkedRecruitmentStatus 연결 장학금의 지금 모집 상태(되돌리지 않았음을 보여 주기 위함) */
	public record Item(
			Long rawId,
			Result result,
			Long linkedScholarshipId,
			String linkedRecruitmentStatus,
			String message
	) {
	}

	public static RawRestoreResultResponse of(List<Item> results) {
		int restored = 0;
		int skipped = 0;
		int failed = 0;
		for (Item item : results) {
			switch (item.result()) {
				case RESTORED -> restored++;
				case SKIPPED -> skipped++;
				case FAILED -> failed++;
			}
		}
		return new RawRestoreResultResponse(results.size(), restored, skipped, failed, NOTICE, List.copyOf(results));
	}
}
