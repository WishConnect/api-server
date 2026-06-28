package com.wishconnect.global.exception;

import lombok.Getter;

/**
 * 비즈니스 로직에서 던지는 공통 예외. {@link ErrorCode} 를 담아
 * {@link GlobalExceptionHandler} 에서 일관된 형태로 응답한다.
 */
@Getter
public class CustomException extends RuntimeException {

	private final ErrorCode errorCode;

	public CustomException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}
}
