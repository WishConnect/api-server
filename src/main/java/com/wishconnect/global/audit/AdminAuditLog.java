package com.wishconnect.global.audit;

import com.wishconnect.global.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자 쓰기 작업 기록.
 *
 * <p>관리자 콘솔을 팀원 여러 명이 쓰게 되면서 필요해졌다. 특히 엑셀 일괄 반영은 한 번에 수백 행을
 * 바꾸는데, 기록이 없으면 잘못된 파일을 올려도 누가 언제 무엇을 바꿨는지 되짚을 수가 없다.
 *
 * <p>{@code actorId} 는 사용자 FK 를 걸지 않고 UUID 값만 저장한다. 감사 기록은 대상이 지워져도
 * 남아야 하는데, FK 가 있으면 회원 탈퇴가 막히거나 기록이 함께 지워진다.
 */
@Entity
@Table(name = "admin_audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAuditLog extends BaseCreatedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "actor_id", nullable = false)
	private UUID actorId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private AdminAction action;

	/** 대상 종류(SCHOLARSHIP, REPORT 등). 전체 대상 작업이면 null. */
	@Column(name = "target_type", length = 50)
	private String targetType;

	/** 대상 식별자. 일괄 작업이면 null 이고 건수는 detail 에 남긴다. */
	@Column(name = "target_id")
	private Long targetId;

	/** 사람이 읽을 요약. 예: "반영 34행, 오류 0행, 파일=edit.xlsx" */
	@Column(length = 1000)
	private String detail;

	@Column(name = "before_json", columnDefinition = "TEXT")
	private String beforeJson;

	@Column(name = "after_json", columnDefinition = "TEXT")
	private String afterJson;

	@Column(name = "restored_at")
	private LocalDateTime restoredAt;

	@Column(name = "restored_by")
	private UUID restoredBy;

	@Builder
	private AdminAuditLog(UUID actorId, AdminAction action, String targetType, Long targetId, String detail,
			String beforeJson, String afterJson) {
		this.actorId = actorId;
		this.action = action;
		this.targetType = targetType;
		this.targetId = targetId;
		this.detail = detail;
		this.beforeJson = beforeJson;
		this.afterJson = afterJson;
	}

	public void markRestored(UUID actorId) {
		this.restoredAt = LocalDateTime.now();
		this.restoredBy = actorId;
	}
}
