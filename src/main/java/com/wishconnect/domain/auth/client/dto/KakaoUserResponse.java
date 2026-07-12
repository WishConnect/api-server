package com.wishconnect.domain.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 사용자 정보 API(v2/user/me) 응답.
 */
public record KakaoUserResponse(
		Long id,
		@JsonProperty("kakao_account") KakaoAccount kakaoAccount
) {
	public record KakaoAccount(
			String email,
			Profile profile
	) {
		public record Profile(String nickname) {
		}
	}

	/** 카카오 계정 이메일 (권한 미동의 시 null). */
	public String email() {
		return kakaoAccount != null ? kakaoAccount.email() : null;
	}

	/** 카카오톡 닉네임 (없으면 null). */
	public String nickname() {
		if (kakaoAccount == null || kakaoAccount.profile() == null) {
			return null;
		}
		return kakaoAccount.profile().nickname();
	}
}
