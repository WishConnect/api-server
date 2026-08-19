package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "관리자 검수용 장학금·원문·조건·서류·이미지 통합 상세")
public record AdminScholarshipDetailResponse(
		ScholarshipData scholarship,
		List<RawData> rawScholarships,
		List<ConditionData> conditions,
		List<DocumentData> documents,
		List<ImageData> images
) {
	public record ScholarshipData(
			Long id, String title, String provider, String summary, String description,
			String scholarshipType, String recruitmentStatus, LocalDateTime applicationStartAt,
			LocalDateTime applicationEndAt, Integer selectionCount, Long amount,
			boolean active, boolean verified, String primarySource, String homepageUrl, String detailUrl,
			String noticeKind, boolean combined, String submissionMethod, String submissionChannel,
			String submissionEvidence, String contact, String essayRequirement, String essayEvidence,
			String interviewRequirement, String interviewEvidence, LocalDateTime createdAt,
			LocalDateTime updatedAt, LocalDateTime deletedAt) {
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
	public record ImageData(Long id, String imageType, String originalName, String contentType,
			Long fileSize, String sourceUrl, String previewUrl) {
	}
}
