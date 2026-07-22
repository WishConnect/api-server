package com.wishconnect.domain.user.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record ProfileAcademicRequest(
		@NotBlank String university,
		@NotBlank String majorCategory,
		@NotBlank String majorName,
		@NotBlank String enrollmentStatus,
		@NotBlank String grade,
		@DecimalMin("0.0") @DecimalMax("4.5") BigDecimal semesterGpa,
		@DecimalMin("0.0") @DecimalMax("4.5") BigDecimal cumulativeGpa,
		String dualMajor
) {
}
