package com.wishconnect.domain.common.dto;

public record AcademicInfoSyncResponse(
		int fetchedSchools,
		int savedSchools,
		int fetchedMajors,
		int savedMajors
) {
}
