package com.wishconnect.domain.auth.dto.response;

import com.wishconnect.domain.user.entity.User;
import java.util.UUID;

public record LoginResponse(
		String accessToken,
		String refreshToken,
		UserInfo user
) {
	public record UserInfo(
			UUID userId,
			String name,
			boolean onboardingCompleted
	) {
	}

	public static LoginResponse of(User user, String accessToken, String refreshToken) {
		return new LoginResponse(accessToken, refreshToken,
				new UserInfo(user.getId(), user.getName(), user.isOnboardingCompleted()));
	}
}
