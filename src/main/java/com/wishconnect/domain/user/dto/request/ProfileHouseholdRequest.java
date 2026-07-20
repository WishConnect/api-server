package com.wishconnect.domain.user.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ProfileHouseholdRequest(
		@NotBlank String incomeLevel,
		@Min(1) Integer familySize,
		List<String> familyTypes,
		List<String> personalStatuses,
		List<String> interests
) {
}
