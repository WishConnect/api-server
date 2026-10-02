package com.wishconnect.global.jwt;

import java.time.Duration;
import org.springframework.http.ResponseCookie;

/** 관리자 화면과 Swagger GET 요청에만 사용하는 HttpOnly 인증 쿠키 이름. */
public final class AdminAuthCookie {

	public static final String NAME = "wc_admin_access";

	private AdminAuthCookie() {
	}

	/** 로그인·연장·로그아웃이 같은 속성으로 쿠키를 만들도록 한곳에 둔다. 빈 값 + 0초면 삭제다. */
	public static ResponseCookie create(String value, long maxAgeSeconds, boolean secure) {
		return ResponseCookie.from(NAME, value)
				.httpOnly(true)
				.secure(secure)
				.sameSite("Strict")
				.path("/")
				.maxAge(Duration.ofSeconds(Math.max(maxAgeSeconds, 0)))
				.build();
	}
}
