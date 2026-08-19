package com.wishconnect.domain.inquiry.entity;

import io.swagger.v3.oas.annotations.media.Schema;

/** 콘텐츠 이용 문의 처리 상태. */
@Schema(description = "콘텐츠 문의 처리 상태: 접수, 처리 완료, 반려")
public enum ContentInquiryStatus {
	PENDING,
	RESOLVED,
	REJECTED
}
