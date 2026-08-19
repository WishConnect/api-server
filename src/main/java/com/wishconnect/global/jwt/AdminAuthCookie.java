package com.wishconnect.global.jwt;

/** 관리자 화면과 Swagger GET 요청에만 사용하는 HttpOnly 인증 쿠키 이름. */
public final class AdminAuthCookie {

	public static final String NAME = "wc_admin_access";

	private AdminAuthCookie() {
	}
}
