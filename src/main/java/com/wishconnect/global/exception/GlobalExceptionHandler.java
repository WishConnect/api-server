package com.wishconnect.global.exception;

import com.wishconnect.global.common.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

	/**
	 * {@code @PreAuthorize} 로 막힌 요청.
	 *
	 * <p>없으면 catch-all 로 떨어져 <b>권한 부족이 500 으로 나간다.</b> 경로 규칙(SecurityConfig)
	 * 으로 막는 엔드포인트는 필터에서 403 이 나므로 이 문제가 드러나지 않았는데, 같은 경로에
	 * 일반 사용자용 메서드와 관리자용 메서드가 함께 있으면 경로 규칙을 쓸 수 없어 어노테이션에
	 * 의존하게 된다. 그 경우를 여기서 받는다.
	 *
	 * <p>401 이 아니라 403 이어야 한다. 401 로 내리면 프론트가 토큰이 만료된 줄 알고
	 * 재로그인을 시킨다.
	 */
	@ExceptionHandler(AuthorizationDeniedException.class)
	public ResponseEntity<ApiResponse<Void>> handleAuthorizationDenied(AuthorizationDeniedException e) {
		log.warn("[AuthorizationDenied] {}", e.getMessage());
		return ResponseEntity.status(ErrorCode.FORBIDDEN.getStatus())
				.body(ApiResponse.fail(ErrorCode.FORBIDDEN.getMessage()));
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

	/**
	 * 매핑된 엔드포인트가 없는 경로(오타, 옛 API 주소, 봇 스캔 등).
	 * catch-all 로 흘러가면 500 + ERROR 로그가 되어, 프론트가 "서버 장애"와 "없는 주소"를
	 * 구분하지 못하고 로그도 오염된다. 서버 잘못이 아니므로 404 + WARN 으로 내린다.
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException e) {
		log.warn("[NoResourceFoundException] {} {}", e.getHttpMethod(), e.getResourcePath());
		return ResponseEntity.status(ErrorCode.ENDPOINT_NOT_FOUND.getStatus())
				.body(ApiResponse.fail(ErrorCode.ENDPOINT_NOT_FOUND.getMessage()));
	}

	/**
	 * 경로는 있으나 HTTP 메서드가 다른 경우(예: GET 전용 경로에 POST).
	 * RFC 9110 상 405 응답은 Allow 헤더를 포함해야 하므로 허용 메서드를 함께 내려준다.
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethodNotSupportedException(
			HttpRequestMethodNotSupportedException e) {
		log.warn("[HttpRequestMethodNotSupportedException] method={} supported={}",
				e.getMethod(), e.getSupportedHttpMethods());
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus());
		Set<HttpMethod> supported = e.getSupportedHttpMethods();
		if (supported != null && !supported.isEmpty()) {
			builder.allow(supported.toArray(new HttpMethod[0]));
		}
		return builder.body(ApiResponse.fail(ErrorCode.METHOD_NOT_ALLOWED.getMessage()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
		log.error("[UnhandledException]", e);
		return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
				.body(ApiResponse.fail(ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
	}
}
