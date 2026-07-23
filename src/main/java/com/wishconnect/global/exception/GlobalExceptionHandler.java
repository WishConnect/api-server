package com.wishconnect.global.exception;

import com.wishconnect.global.common.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 전역 예외 처리기. 모든 예외를 {@link ApiResponse} 형태(success=false)로 통일 응답한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(CustomException.class)
	public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException e) {
		ErrorCode errorCode = e.getErrorCode();
		log.warn("[CustomException] {} - {}", errorCode.name(), errorCode.getMessage());
		return ResponseEntity.status(errorCode.getStatus())
				.body(ApiResponse.fail(errorCode.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
		log.warn("[ValidationException] {}", e.getMessage());
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
				.body(ApiResponse.fail(ErrorCode.INVALID_INPUT.getMessage()));
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(ConstraintViolationException e) {
		log.warn("[ConstraintViolationException] {}", e.getMessage());
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
				.body(ApiResponse.fail(ErrorCode.INVALID_INPUT.getMessage()));
	}

	/**
	 * 요청 바디를 읽지 못한 경우(잘못된 JSON 문법, 타입 불일치, enum 값 오타, 바디 누락 등).
	 * 클라이언트 입력 문제이므로 500이 아니라 400으로 응답한다.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleNotReadableException(HttpMessageNotReadableException e) {
		log.warn("[HttpMessageNotReadableException] {}", e.getMessage());
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
				.body(ApiResponse.fail(ErrorCode.INVALID_INPUT.getMessage()));
	}

	/** 쿼리 파라미터 타입 불일치(예: page=abc). */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Void>> handleTypeMismatchException(MethodArgumentTypeMismatchException e) {
		log.warn("[MethodArgumentTypeMismatchException] param={} value={}", e.getName(), e.getValue());
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
				.body(ApiResponse.fail(ErrorCode.INVALID_INPUT.getMessage()));
	}

	/** 필수 쿼리 파라미터 누락. */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingParameterException(
			MissingServletRequestParameterException e) {
		log.warn("[MissingServletRequestParameterException] param={}", e.getParameterName());
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
				.body(ApiResponse.fail(ErrorCode.INVALID_INPUT.getMessage()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
		log.error("[UnhandledException]", e);
		return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
				.body(ApiResponse.fail(ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
	}
}
