package com.wishconnect.domain.user.entity;

/**
 * 로그인 방식 구분. 기본(이메일/비밀번호) 또는 소셜(카카오/구글/네이버).
 */
public enum LoginType {
	LOCAL,
	KAKAO,
	GOOGLE,
	NAVER
}
