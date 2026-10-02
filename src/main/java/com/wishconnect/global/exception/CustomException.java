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

	/**
	 * 원인 예외를 보존한다. 5xx 로 바꿔 던질 때는 반드시 이쪽을 쓴다.
	 *
	 * <p>cause 를 끊으면 {@link GlobalExceptionHandler} 가 남기는 로그에 원인이 빠져, 장애 원인을
	 * 찾을 수 없다(2026-09 병합 승인 500 이 이 때문에 콘솔 로그에서 보이지 않았다).
	 */
	public CustomException(ErrorCode errorCode, Throwable cause) {
		super(errorCode.getMessage(), cause);
		this.errorCode = errorCode;
	}
}
