package com.wishconnect.domain.scholarship.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 관리자가 외부 포스터 URL을 등록하거나 교체할 때 사용한다. */
public record AdminImageUrlRequest(
		@NotBlank @Size(max = 1000) String imageUrl
) {
}
