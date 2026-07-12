package com.wishconnect.domain.auth.client.dto;

/**
 * 네이버 사용자 정보 API(v1/nid/me) 응답. 실제 프로필은 {@code response} 안에 담긴다.
 * providerId 는 {@code response.id}.
 */
public record NaverUserResponse(
		String resultcode,
		String message,
		Response response
) {
	public record Response(
			String id,
			String email,
			String name,
			String nickname
	) {
	}

	public String id() {
		return response != null ? response.id() : null;
	}

	public String email() {
		return response != null ? response.email() : null;
	}

	/** 표시 이름: name 우선, 없으면 nickname. */
	public String displayName() {
		if (response == null) {
			return null;
		}
		return response.name() != null ? response.name() : response.nickname();
	}
}
