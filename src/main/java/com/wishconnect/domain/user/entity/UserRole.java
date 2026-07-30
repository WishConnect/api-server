package com.wishconnect.domain.user.entity;

/**
 * 사용자 권한. 일반 가입자는 모두 {@link #USER} 이며,
 * {@link #ADMIN} 은 운영용 수동 트리거(동기화·크롤링·LLM 추출) 호출 권한을 가진다.
 *
 * <p>ADMIN 승격은 가입 경로가 없고 DB 에서 직접 부여한다.
 */
public enum UserRole {

	USER,
	ADMIN;

	/** Spring Security 권한 문자열(ROLE_ 접두사 포함). */
	public String authority() {
		return "ROLE_" + name();
	}
}
