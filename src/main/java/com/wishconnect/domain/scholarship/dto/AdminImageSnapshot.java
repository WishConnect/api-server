package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.common.entity.Image;

/**
 * 포스터 이미지 교체 기록용 스냅샷(작업 기록 before/after). 교체해도 이전 S3 객체는 지우지 않으므로,
 * 이 기록의 s3Key·sourceUrl 로 이전 이미지를 찾아 다시 연결할 수 있다. 서명 URL 은 만료되므로 남기지 않는다.
 */
public record AdminImageSnapshot(
		Long imageId,
		String s3Key,
		String originalName,
		String contentType,
		Long fileSize,
		String sourceUrl
) {
	public static AdminImageSnapshot from(Image image) {
		return image == null ? null : new AdminImageSnapshot(image.getId(), image.getS3Key(), image.getOriginalName(),
				image.getContentType(), image.getFileSize(), image.getSourceUrl());
	}

	/** 교체 결과: 응답에 쓸 미리보기 URL 과 기록용 전후 스냅샷(처음 등록이면 before 는 null). */
	public record Replaced(String url, AdminImageSnapshot before, AdminImageSnapshot after) {
	}
}
