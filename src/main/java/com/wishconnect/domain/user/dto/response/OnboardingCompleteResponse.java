package com.wishconnect.domain.user.dto.response;

import java.util.UUID;

public record OnboardingCompleteResponse(
		boolean onboardingCompleted,
		UUID recommendationJobId
) {
}
