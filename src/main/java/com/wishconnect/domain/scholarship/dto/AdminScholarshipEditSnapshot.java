package com.wishconnect.domain.scholarship.dto;

import java.util.List;

/** 통합 수기 수정 감사로그용 스냅샷. 대용량 원문과 만료되는 이미지 URL은 제외한다. */
public record AdminScholarshipEditSnapshot(
		AdminScholarshipDetailResponse.ScholarshipData scholarship,
		List<AdminScholarshipDetailResponse.ConditionData> conditions,
		List<AdminScholarshipDetailResponse.DocumentData> documents
) {
	public static AdminScholarshipEditSnapshot from(AdminScholarshipDetailResponse detail) {
		return new AdminScholarshipEditSnapshot(
				detail.scholarship(), List.copyOf(detail.conditions()), List.copyOf(detail.documents()));
	}
}
