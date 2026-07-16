package com.wishconnect.domain.application.entity;

/**
 * 자기소개서(지원서) 작성 상태. Notion API 명세의 essay.status 값에 대응된다.
 *
 * <ul>
 *   <li>NOT_STARTED — 지원서 생성 직후 (문항만 준비되고 답변은 비어 있음)</li>
 *   <li>IN_PROGRESS — 인터뷰/답변 작성이 시작된 상태</li>
 *   <li>COMPLETED  — 모든 문항이 완료 확정된 상태</li>
 * </ul>
 */
public enum EssayStatus {
	NOT_STARTED,
	IN_PROGRESS,
	COMPLETED
}
