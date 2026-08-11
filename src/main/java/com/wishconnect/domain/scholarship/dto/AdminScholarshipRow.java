package com.wishconnect.domain.scholarship.dto;

import java.time.LocalDateTime;

/**
 * 관리자 목록 한 줄. 파싱이 제대로 됐는지 눈으로 훑기 위한 화면용이라
 * 본문 전체 대신 <b>비어 있는지 여부</b>를 내려준다.
 */
public record AdminScholarshipRow(
		Long scholarshipId,
		String title,
		String provider,
		String source,
		String recruitmentStatus,
		LocalDateTime applicationEndAt,
		LocalDateTime createdAt,
		boolean hasSummary,
		boolean hasAmount,
		boolean hasHomepageUrl,
		boolean hasPoster,
		boolean softDeleted
) {
}
