package com.wishconnect.global.operation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "배치 실패 상세 한 건")
public record AdminJobFailureResponse(
		Long id,
		Long jobRunId,
		@Schema(description = "단계 이름(예: 대학 공지 LLM 파싱)") String step,
		@Schema(description = "RAW_SCHOLARSHIP / SCHOLARSHIP / SOURCE / GROUP / STEP") String targetType,
		@Schema(description = "원문·장학금 ID. 출처·단계 단위 실패면 null") Long targetId,
		@Schema(description = "제목·출처 코드 등") String targetLabel,
		AdminJobFailureType failureType,
		@Schema(description = "유형 한국어 이름") String failureTypeLabel,
		@Schema(description = "결제·키 수정 등 사람이 조치해야 하는 유형인지") boolean needsOperatorAction,
		String reason,
		LocalDateTime createdAt
) {
	public static AdminJobFailureResponse from(AdminJobFailure value) {
		return new AdminJobFailureResponse(value.getId(), value.getJobRunId(), value.getStep(),
				value.getTargetType(), value.getTargetId(), value.getTargetLabel(), value.getFailureType(),
				value.getFailureType().label(), value.getFailureType().needsOperatorAction(), value.getReason(),
				value.getCreatedAt());
	}
}
