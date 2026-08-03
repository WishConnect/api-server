package com.wishconnect.domain.common.dto;

import com.wishconnect.domain.common.entity.MajorCategory;

public record MajorResponse(
		Long id,
		String name,
		/** JSON 에는 한글 표기(예: "공학계열")로 나간다. */
		MajorCategory category
) {
}
