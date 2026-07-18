package com.wishconnect.domain.scholarship.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 장학금 상세 응답. 노션 명세(GET /api/v1/scholarships/{scholarshipId}) 구조.
 * summary = 요약 정보 테이블(조건 원문 매핑), selectionSchedule = 선발 일정 타임라인,
 * requiredDocuments = 제출 서류 목록.
 */
public record ScholarshipDetailResponse(
		Long scholarshipId,
		String title,
		String organization,
		String status,
		LocalDateTime deadline,
		Long dDay,
		boolean isScrapped,
		List<String> tags,
		String posterUrl,
		String detailUrl,
		Summary summary,
		List<ScheduleStep> selectionSchedule,
		List<RequiredDocument> requiredDocuments,
		List<String> matchReasons
) {

	public record Summary(
			String targetAudience,
			String supportAmount,
			String selectedCount,
			String fieldOfStudy,
			String supportType,
			String duplicateAllowed,
			String operatingOrganization,
			String contactInfo,
			String selectionCriteria,
			String gpaRequirement,
			String incomeRequirement,
			String preferredConditions,
			String applicationPeriod,
			String submissionMethod
	) {
	}

	public record ScheduleStep(String step, String date, String status) {
	}

	public record RequiredDocument(String name, String downloadUrl) {
	}
}
