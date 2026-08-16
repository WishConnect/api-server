package com.wishconnect.domain.user.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;

public record ProfileBasicRequest(
		@NotBlank String name,
		@NotNull LocalDate birthDate,
		@NotBlank String phone,
		@NotBlank String gender,
		@NotBlank String nationality,
		@NotBlank String region
) {
}
