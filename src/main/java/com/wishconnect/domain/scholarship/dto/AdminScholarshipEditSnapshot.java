package com.wishconnect.domain.scholarship.dto;

import java.util.List;

/**
 * 통합 수기 수정 감사로그용 스냅샷. 대용량 원문과 만료되는 이미지 URL은 제외한다.
 *
 * <p>모집기간 수기 고정은 {@code scholarship.periodLocked} 에 들어 있다. 2026-10 이전 기록에는 {@code timeline}
 * 키와 {@code periodLocked} 가 없다 — 그런 기록으로 복구할 때는 현재 일정·고정 상태를 그대로 둔다
 * ({@code ScholarshipFieldRestorer}).
 */
public record AdminScholarshipEditSnapshot(
		AdminScholarshipDetailResponse.ScholarshipData scholarship,
		List<AdminScholarshipDetailResponse.ConditionData> conditions,
		List<AdminScholarshipDetailResponse.DocumentData> documents,
		List<AdminScholarshipDetailResponse.TimelineData> timeline
) {
	public static AdminScholarshipEditSnapshot from(AdminScholarshipDetailResponse detail) {
		return new AdminScholarshipEditSnapshot(
				detail.scholarship(), List.copyOf(detail.conditions()), List.copyOf(detail.documents()),
				detail.timeline() == null ? List.of() : List.copyOf(detail.timeline()));
	}
}
