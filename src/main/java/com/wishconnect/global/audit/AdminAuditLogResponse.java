package com.wishconnect.global.audit;

import java.time.LocalDateTime;
import java.util.UUID;

/** 감사 로그 한 줄. 관리자 화면에서 최근 작업 이력을 훑는 용도다. */
public record AdminAuditLogResponse(
		Long id,
		UUID actorId,
		AdminAction action,
		String targetType,
		Long targetId,
		String detail,
		String beforeJson,
		String afterJson,
		LocalDateTime restoredAt,
		UUID restoredBy,
		LocalDateTime createdAt,
		/** 복구 미리보기·복구를 쓸 수 있는 기록인지(콘솔의 복구 버튼 표시용) */
		boolean restorable
) {

	public static AdminAuditLogResponse from(AdminAuditLog log) {
		return new AdminAuditLogResponse(
				log.getId(), log.getActorId(), log.getAction(),
				log.getTargetType(), log.getTargetId(), log.getDetail(), log.getBeforeJson(),
				log.getAfterJson(), log.getRestoredAt(), log.getRestoredBy(), log.getCreatedAt(),
				AdminAuditRestoreService.isRestorable(log));
	}
}
