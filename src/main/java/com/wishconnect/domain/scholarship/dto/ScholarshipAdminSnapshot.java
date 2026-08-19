package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import java.time.LocalDateTime;

/** 관리자 수기 수정·내리기에서 복구 가능한 장학금 필드 스냅샷. */
public record ScholarshipAdminSnapshot(
		String title,
		String provider,
		String summary,
		String description,
		ScholarshipType scholarshipType,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		RecruitmentStatus recruitmentStatus,
		Integer selectionCount,
		Long amount,
		String homepageUrl,
		boolean active,
		boolean verified,
		LocalDateTime deletedAt
) {
	public static ScholarshipAdminSnapshot from(Scholarship value) {
		return new ScholarshipAdminSnapshot(value.getTitle(), value.getProvider(), value.getSummary(),
				value.getDescription(), value.getScholarshipType(), value.getApplicationStartAt(),
				value.getApplicationEndAt(), value.getRecruitmentStatus(), value.getSelectionCount(),
				value.getAmount(), value.getHomepageUrl(), value.isActive(), value.isVerified(),
				value.getDeletedAt());
	}
}
