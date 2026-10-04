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
		boolean softDeleted,
		/** 내린 시각. 내리지 않았으면 null */
		LocalDateTime deletedAt,
		/** 내린 관리자 ID. 수집 배치가 스스로 내린 경우는 null */
		java.util.UUID deletedBy,
		/** 내린 관리자 이름(조회 가능할 때) */
		String deletedByName,
		/** 내린 사유. 병합이면 "병합: #N 로 합쳐짐" */
		String deleteReason,
		/** 삭제 구분: ADMIN(관리자 내리기) / MERGE(병합) / SYSTEM(수집 배치) / null(삭제 아님) */
		String deleteKind
) {
}
