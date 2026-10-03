package com.wishconnect.global.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "감사 로그 복구 결과")
public record AuditRestoreResultResponse(
		Long logId,
		Long targetId,
		@Schema(description = "실제로 되돌린 필드") List<String> restoredFields,
		@Schema(description = "복구를 남긴 새 감사 기록 ID(이 기록으로 복구를 다시 되돌릴 수 있다)") Long restoreLogId
) {
}
