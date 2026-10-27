package com.wishconnect.global.audit;

import java.time.LocalDateTime;
import java.util.Map;
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
		boolean restorable,
		/** 관리자 이름(없으면 로그인 아이디). 계정이 없어졌으면 null — 화면은 UUID 앞 8자리를 보여 준다. 이메일·연락처는 싣지 않는다 */
		String actorName,
		/**
		 * 이 기록이 가리키는 장학금(대상 + 병합·반려 요약에 적힌 장학금)의 제목과 내려짐 여부. 지금 값이라 기록 당시
		 * 제목과 다를 수 있다. 장학금이 아닌 대상이면 빈 맵
		 */
		Map<Long, ScholarshipRef> scholarships
) {

	/** 작업 기록 화면에 붙일 장학금 이름표. */
	public record ScholarshipRef(String title, boolean deleted) {
	}

	public static AdminAuditLogResponse from(AdminAuditLog log) {
		return of(log, null, Map.of());
	}

	public static AdminAuditLogResponse of(AdminAuditLog log, String actorName, Map<Long, ScholarshipRef> scholarships) {
		return new AdminAuditLogResponse(
				log.getId(), log.getActorId(), log.getAction(),
				log.getTargetType(), log.getTargetId(), log.getDetail(), log.getBeforeJson(),
				log.getAfterJson(), log.getRestoredAt(), log.getRestoredBy(), log.getCreatedAt(),
				AdminAuditRestoreService.isRestorable(log), actorName, scholarships);
	}
}
