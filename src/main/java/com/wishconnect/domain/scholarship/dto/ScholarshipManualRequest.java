package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 장학금 수기 등록·수정 요청.
 *
 * <p>등록 시에는 {@code title} 이 필수다. 수정 시에는 보낸 필드만 반영되고
 * 생략한 필드는 기존 값이 유지된다(오등록 신고분의 한두 칸만 고치는 게 주 용도).
 */
public record ScholarshipManualRequest(
		@Schema(description = "장학금명", example = "2026 미래인재 성장 장학금")
		@Size(max = 500) String title,

		@Schema(description = "운영기관", example = "한국장학재단")
		@Size(max = 200) String provider,

		@Schema(description = "한 줄 요약") String summary,

		@Schema(description = "상세 설명") String description,

		@Schema(description = "구분", example = "EXTERNAL") ScholarshipType scholarshipType,

		@Schema(description = "모집 시작일시") LocalDateTime applicationStartAt,

		@Schema(description = "모집 종료일시") LocalDateTime applicationEndAt,

		@Schema(description = "선발 인원") @PositiveOrZero Integer selectionCount,

		@Schema(description = "지원 금액(원)") @PositiveOrZero Long amount,

		@Schema(description = "신청 페이지 URL") @Size(max = 1000) String homepageUrl
		,
		@Schema(description = "관리자가 확인한 모집 상태") RecruitmentStatus recruitmentStatus
) {

	/** 등록에 필요한 최소 조건. 수정에는 적용하지 않는다. */
	public record Create(
			@NotBlank @Size(max = 500) String title,
			@Size(max = 200) String provider,
			String summary,
			String description,
			ScholarshipType scholarshipType,
			LocalDateTime applicationStartAt,
			LocalDateTime applicationEndAt,
			@PositiveOrZero Integer selectionCount,
			@PositiveOrZero Long amount,
			@Size(max = 1000) String homepageUrl
	) {
	}
}
