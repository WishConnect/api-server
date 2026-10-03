package com.wishconnect.global.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "감사 로그 필드 선택 복구 요청")
public record AuditRestoreRequest(
		@Schema(description = "되돌릴 필드(미리보기 fields[].field 값)", example = "[\"title\", \"provider\"]")
		@NotEmpty List<String> fields,
		@Schema(description = "복구 사유(감사 기록에 남는다)") @Size(max = 300) String reason
) {
}
