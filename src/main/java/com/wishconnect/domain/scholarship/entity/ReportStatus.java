package com.wishconnect.domain.scholarship.entity;

/** 오등록 신고 처리 상태. */
public enum ReportStatus {
	/** 접수됨. 관리자 확인 대기 */
	PENDING,
	/** 신고가 맞아 데이터를 고쳤거나 내림 */
	RESOLVED,
	/** 확인 결과 현재 데이터가 맞아 반려 */
	REJECTED
}
