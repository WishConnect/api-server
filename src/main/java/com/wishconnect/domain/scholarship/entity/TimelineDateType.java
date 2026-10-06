package com.wishconnect.domain.scholarship.entity;

/**
 * 선발 일정 날짜 모양. DB CHECK({@code scholarship_timeline_date_shape_check})와 같은 규칙이다.
 */
public enum TimelineDateType {

	/** 하루. startDate = endDate */
	SINGLE,

	/** 기간. startDate ≤ endDate */
	RANGE,

	/** 미정. 날짜 없이 dateText 만 있다 */
	TBD
}
