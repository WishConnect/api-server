package com.wishconnect.domain.auth.dto.response;

import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import java.util.UUID;

/**
 * 소셜(구글/네이버) 로그인 응답. 카카오와 동일한 구조.
 */
public record SocialLoginResponse(
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

	public static SocialLoginResponse of(User user, String accessToken, String refreshToken, boolean isNewUser) {
		return new SocialLoginResponse(accessToken, refreshToken, isNewUser,
				new UserInfo(user.getId(), user.getName(), user.getLoginType(), user.isOnboardingCompleted()));
	}
}
