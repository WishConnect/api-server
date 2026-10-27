package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 원본 만료 처리 되돌리기 요청(1~100건). */
@Schema(description = "실패 원본 만료 처리 되돌리기 요청")
public record RawRestoreRequest(
		@Schema(description = "되돌릴 원본 ID(1~100건)", example = "[8766]") List<Long> rawIds
) {
}
