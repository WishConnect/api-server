package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 중복 후보 반려·반려 취소 사유. URL 쿼리로 보내면 접근 로그에 남고 길이 제한도 URL 기준이라 본문으로 받는다. */
@Schema(description = "중복 후보 반려·반려 취소 사유")
public record MergeCandidateNoteRequest(
		@Schema(description = "사유", example = "캠퍼스가 다른 별개 모집") @Size(max = 1000) String note
) {
}
