package com.wishconnect.domain.scholarship.entity;

/**
 * 선발 일정 단계. {@link #CUSTOM} 을 뺀 표준 단계는 표시명을 비워 두면 {@link #defaultTitle()} 로 채운다.
 *
 * <p>값을 더하면 마이그레이션의 {@code scholarship_timeline_stage_code_check} 도 같이 고칠 것
 * ({@code MigrationCheckConstraintTest} 가 대조한다).
 */
public enum TimelineStageCode {

	/** 접수. 이 행이 있으면 사용자 화면이 모집기간으로 "서류접수" 줄을 따로 만들지 않는다. */
	APPLICATION("서류접수"),
	DOC_REVIEW("서류심사"),
	DOC_RESULT("서류 발표"),
	INTERVIEW("면접"),
	FINAL_RESULT("최종 발표"),
	PAYMENT("장학금 지급"),
	/** 그 밖의 단계. 표시명 필수 */
	CUSTOM(null);

	private final String defaultTitle;

	TimelineStageCode(String defaultTitle) {
		this.defaultTitle = defaultTitle;
	}

	/** 표준 단계의 기본 표시명. CUSTOM 은 null. */
	public String defaultTitle() {
		return defaultTitle;
	}
}
