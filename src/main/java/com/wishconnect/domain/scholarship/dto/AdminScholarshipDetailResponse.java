package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "관리자 검수용 장학금·원문·조건·서류·선발 일정·이미지 통합 상세")
public record AdminScholarshipDetailResponse(
		ScholarshipData scholarship,
		List<RawData> rawScholarships,
		List<ConditionData> conditions,
		List<DocumentData> documents,
		@Schema(description = "선발 일정(표시 순서대로). 통합 수정 저장 때 이 목록을 그대로 timeline 에 담아 보낸다")
		List<TimelineData> timeline,
		List<ImageData> images,
		/** 모집 상태·기간 모순 점검. 통합 수정 화면이 저장 전 경고에 쓴다(서버는 상태를 바꾸지 않는다). */
		RecruitmentStatusCheck statusCheck
) {
	public record ScholarshipData(
			Long id, String title, String provider, String summary, String description,
			String scholarshipType, String recruitmentStatus, LocalDateTime applicationStartAt,
			LocalDateTime applicationEndAt, Integer selectionCount, Long amount,
			boolean active, boolean verified, String primarySource, String homepageUrl, String detailUrl,
			String noticeKind, boolean combined, String submissionMethod, String submissionChannel,
			String submissionEvidence, String contact, String essayRequirement, String essayEvidence,
			String interviewRequirement, String interviewEvidence, LocalDateTime createdAt,
			LocalDateTime updatedAt, LocalDateTime deletedAt,
            @Schema(description = "모집기간 수기 고정. true 면 동기화·재파싱이 기간을 덮지 않는다") boolean periodLocked,
            @Schema(description = "선발 일정 수기 보호. 빈 목록으로 저장한 경우도 자동 보완하지 않는다") boolean timelineLocked) {
	}
	public record RawData(Long id, String source, String sourceId, String sourceUrl,
			Map<String, Object> rawJson, String rawHtml, String parseStatus, String parseError,
			LocalDateTime crawledAt) {
	}
	public record ConditionData(Long id, String conditionType, String operator, String necessity,
			Integer valueInt, Integer valueIntMax, String valueString, boolean autoExtracted,
			List<RefData> refs) {
	}
	public record RefData(Long refId, String refCode) {
	}
	public record DocumentData(Long id, String name, boolean essay, int displayOrder, String downloadUrl) {
	}
	/** 선발 일정 한 행. 생성·수정 시각은 뺀다 — 저장할 때마다 행을 새로 만들어 감사 비교가 늘 "바뀜"이 된다. */
	public record TimelineData(Long id, String stageCode, String title, String dateType, LocalDate startDate,
			LocalDate endDate, String dateText, String note, String evidence, String origin, int displayOrder) {
	}
	public record ImageData(Long id, String imageType, String originalName, String contentType,
			Long fileSize, String sourceUrl, String previewUrl) {
	}
}
