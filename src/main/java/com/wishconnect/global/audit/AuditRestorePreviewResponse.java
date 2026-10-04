package com.wishconnect.global.audit;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 감사 로그 복구 미리보기. 기록 시점 값과 현재 값을 필드별로 나란히 보여 주고, 그 사이 다른 변경이 있었는지 알린다.
 */
@Schema(description = "감사 로그 복구 미리보기")
public record AuditRestorePreviewResponse(
		Long logId,
		AdminAction action,
		String targetType,
		Long targetId,
		@Schema(description = "기록 시각") LocalDateTime recordedAt,
		@Schema(description = "기록한 관리자") UUID recordedBy,
		@Schema(description = "복구할 수 있는 기록인지") boolean restorable,
		@Schema(description = "복구할 수 없는 이유(restorable=false 일 때)") String notRestorableReason,
		@Schema(description = "이미 복구한 시각(한 기록은 한 번만 복구)") LocalDateTime restoredAt,
		@Schema(description = "기록 이후 이 장학금에 다른 변경이 있었는지(감사 기록 또는 값 비교로 감지)")
		boolean changedSinceRecord,
		@Schema(description = "기록 이후의 같은 장학금 감사 기록(최신 20건)") List<LaterChange> laterChanges,
		@Schema(description = "이 기록에서 바뀐 필드들") List<FieldPreview> fields
) {

	@Schema(description = "기록 이후의 변경")
	public record LaterChange(Long logId, AdminAction action, UUID actorId, LocalDateTime createdAt, String detail) {
	}

	@Schema(description = "필드 하나의 비교")
	public record FieldPreview(
			String field,
			@Schema(description = "한국어 이름") String label,
			@Schema(description = "되돌릴 값(기록 직전 값)") JsonNode recordedValue,
			@Schema(description = "기록 직후 값") JsonNode loggedValue,
			@Schema(description = "현재 값") JsonNode currentValue,
			@Schema(description = "복구하면 현재 값이 바뀌는지") boolean differsFromCurrent,
			@Schema(description = "기록 이후 이 필드가 다시 바뀌었는지. true 면 복구가 그 변경을 덮어쓴다")
			boolean changedSinceRecord,
			@Schema(description = "화면 체크박스 기본값. 모집 상태와 이후에 다시 바뀐 필드는 기본 선택 안 함")
			boolean defaultSelected,
			@Schema(description = "주의 문구(있으면)") String warning
	) {
	}
}
