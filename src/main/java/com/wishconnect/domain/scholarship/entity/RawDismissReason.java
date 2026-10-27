package com.wishconnect.domain.scholarship.entity;

/**
 * 실패 재처리 원본을 "만료 처리"(목록에서 숨기기)한 사유. raw_scholarship.dismissed_reason CHECK 와 값이 같아야 한다
 * (V20261026_01, MigrationCheckConstraintTest).
 */
public enum RawDismissReason {
	/** 모집기간이 지난 공고. 파싱 실패로 마감일을 못 읽어 자동 제외되지 않은 것. */
	PERIOD_EXPIRED,
	/** 장학금 공고가 아님(행사·결과 안내 등). */
	NOT_SCHOLARSHIP,
	/** 다른 원본·장학금과 같은 공고. */
	DUPLICATE,
	/** 기타. 메모가 필수다. */
	OTHER
}
