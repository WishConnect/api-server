package com.wishconnect.domain.scholarship.dto;

public record ScholarshipAdminChangeResult(
		ScholarshipManualResponse response,
		ScholarshipAdminSnapshot before,
		ScholarshipAdminSnapshot after
) {
}
