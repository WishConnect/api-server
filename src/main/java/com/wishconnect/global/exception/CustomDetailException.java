package com.wishconnect.global.exception;

import lombok.Getter;

/**
 * 실패 응답의 {@code data} 에 화면이 써야 할 정보를 싣는 예외.
 *
 * <p>예: 관리자 로그인 실패 시 "현재 실패 횟수 / 잠금 기준 / 남은 잠금 시간". 메시지 문자열만으로는
 * 화면이 숫자를 다시 파싱해야 하므로 구조화된 값으로 따로 내린다.
 */
@Getter
public class CustomDetailException extends CustomException {

	private final transient Object detail;

	public CustomDetailException(ErrorCode errorCode, Object detail) {
		super(errorCode);
		this.detail = detail;
	}
}
