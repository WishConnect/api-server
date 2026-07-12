package com.wishconnect.domain.notification.entity;

/**
 * 알림 유형.
 * ⚠️ 값 확정 필요 — ERD에 값 미정의. 알림 설정(매칭/일정/에세이) 기준으로 추정.
 */
public enum NotificationType {
	SCHOLARSHIP_MATCH,   // 장학금 매칭
	SCHEDULE_REMINDER,   // 일정(마감 등) 알림
	ESSAY_REMINDER,      // 에세이 작성 알림
	SYSTEM,              // 시스템 공지
	ANNOUNCEMENT         // 일반 공지
}
