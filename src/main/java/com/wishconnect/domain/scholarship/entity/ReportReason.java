package com.wishconnect.domain.scholarship.entity;

/** 오등록 신고 사유. 프론트 선택지와 1:1 대응한다. */
public enum ReportReason {
	/** 모집 기간·마감일이 실제와 다름 */
	WRONG_DEADLINE,
	/** 지원 금액이 실제와 다름 */
	WRONG_AMOUNT,
	/** 신청 링크가 깨졌거나 다른 곳으로 감 */
	BROKEN_LINK,
	/** 이미 마감됐는데 모집 중으로 표시됨 */
	ALREADY_CLOSED,
	/** 같은 장학금이 중복 등록됨 */
	DUPLICATE,
	/** 그 밖의 사유(detail 에 상세 기재) */
	OTHER
}
