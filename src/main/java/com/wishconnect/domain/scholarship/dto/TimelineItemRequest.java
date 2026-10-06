package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * 선발 일정 한 단계 입력.
 *
 * <p>단계·날짜 형태를 enum 이 아니라 문자열로 받는 이유: 잘못된 값이 JSON 역직렬화에서 막히면 공통
 * INVALID_INPUT 하나로 뭉개져 몇 번째 행이 틀렸는지 알 수 없다. 서버가 직접 읽어 행 번호와 필드를 담은
 * 오류(TIMELINE_*)로 돌려준다. 길이 제한도 같은 이유로 Bean Validation 대신 서버 검증에서 본다.
 */
@Schema(description = "선발 일정 한 단계. 순서(display_order)는 배열 순서로 서버가 매긴다")
public record TimelineItemRequest(
		@Schema(description = "단계 코드. CUSTOM 이 아니면 title 을 비워도 기본 표시명이 들어간다",
				requiredMode = Schema.RequiredMode.REQUIRED,
				allowableValues = {"APPLICATION", "DOC_REVIEW", "DOC_RESULT", "INTERVIEW", "FINAL_RESULT", "PAYMENT",
						"CUSTOM"},
				example = "INTERVIEW")
		String stageCode,
		@Schema(description = "표시명(50자). CUSTOM 이면 필수. 비우면 APPLICATION 서류접수, DOC_REVIEW 서류심사, "
				+ "DOC_RESULT 서류 발표, INTERVIEW 면접, FINAL_RESULT 최종 발표, PAYMENT 장학금 지급",
				example = "면접")
		String title,
		@Schema(description = "날짜 형태. SINGLE 하루(startDate, endDate 생략 시 startDate 로 채움) / "
				+ "RANGE 기간(startDate ≤ endDate) / TBD 미정(날짜 없이 dateText 필수)",
				requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"SINGLE", "RANGE", "TBD"},
				example = "SINGLE")
		String dateType,
		@Schema(description = "시작일(SINGLE·RANGE 필수, TBD 는 비워야 함)", example = "2026-11-20")
		LocalDate startDate,
		@Schema(description = "종료일(RANGE 필수, SINGLE 은 생략하거나 startDate 와 같게, TBD 는 비워야 함)",
				example = "2026-11-20")
		LocalDate endDate,
		@Schema(description = "미정 일정 표시 문구(100자, TBD 필수). TBD 가 아니면 저장하지 않는다", example = "12월 중 예정")
		String dateText,
		@Schema(description = "비고(200자). 예: 18:00 마감, 트랙 구분", example = "합격자 개별 통보")
		String note,
		@Schema(description = "공고 원문 근거 문장(2000자)")
		String evidence
) {
}
