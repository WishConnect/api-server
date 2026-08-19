package com.wishconnect.domain.scholarship.dto;

import java.time.LocalDateTime;

/** 이미지 관리 목록. 이미지 없는 장학금도 imageId/previewUrl 이 null 인 행으로 반환한다. */
public record AdminImageRowResponse(
		Long scholarshipId,
		String scholarshipTitle,
		String provider,
		String source,
		Long imageId,
		String imageType,
		String originalName,
		String sourceUrl,
		String previewUrl,
		LocalDateTime imageCreatedAt
) {
}
