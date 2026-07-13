package com.wishconnect.domain.auth.dto.response;

import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import java.util.UUID;

public record KakaoLoginResponse(
		String accessToken,
		String refreshToken,
		boolean isNewUser,
		UserInfo user
) {
	public record UserInfo(
			UUID userId,
			String name,
			LoginType loginType,
			boolean onboardingCompleted
	) {
	}

	public static KakaoLoginResponse of(User user, String accessToken, String refreshToken, boolean isNewUser) {
		return new KakaoLoginResponse(accessToken, refreshToken, isNewUser,
				new UserInfo(user.getId(), user.getName(), user.getLoginType(), user.isOnboardingCompleted()));
	}
}
