package com.wishconnect.domain.inquiry.entity;

import io.swagger.v3.oas.annotations.media.Schema;

/** 콘텐츠 이용 문의 유형. 화면의 선택 항목과 1:1로 대응한다. */
@Schema(description = "콘텐츠 이용 문의 유형: 포스터/이미지 이용 중단, 장학금 정보 게시 중단, 저작권 침해, 정보 수정, 기타")
public enum ContentInquiryType {
	POSTER_IMAGE_TAKEDOWN,
	SCHOLARSHIP_TAKEDOWN,
	COPYRIGHT_INFRINGEMENT,
	INFORMATION_CORRECTION,
	OTHER
}
