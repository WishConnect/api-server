package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import java.time.LocalDateTime;

/** 수기 등록·수정 결과. 저장된 값을 그대로 돌려줘 관리자가 반영 결과를 확인한다. */
public record ScholarshipManualResponse(
		Long scholarshipId,
		String title,
		String provider,
		ScholarshipType scholarshipType,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		RecruitmentStatus recruitmentStatus,
		Integer selectionCount,
		Long amount,
		String homepageUrl,
		String primarySource,
		boolean active,
		boolean verified
) {

	public static ScholarshipManualResponse from(Scholarship scholarship) {
		return new ScholarshipManualResponse(
				scholarship.getId(),
				scholarship.getTitle(),
				scholarship.getProvider(),
				scholarship.getScholarshipType(),
				scholarship.getApplicationStartAt(),
				scholarship.getApplicationEndAt(),
				scholarship.getRecruitmentStatus(),
				scholarship.getSelectionCount(),
				scholarship.getAmount(),
				scholarship.getHomepageUrl(),
				scholarship.getPrimarySource(),
				scholarship.isActive(),
				scholarship.isVerified()
		);
	}
}
