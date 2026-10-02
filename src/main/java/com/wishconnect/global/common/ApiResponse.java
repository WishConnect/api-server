package com.wishconnect.global.common;

/**
 * 모든 API 응답을 감싸는 공통 응답 포맷.
 * <pre>
 * { "success": true|false, "data": {...}|null, "message": null|"에러 메시지" }
 * </pre>
 */
public record ApiResponse<T>(boolean success, T data, String message) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, data, null);
	}

	public static ApiResponse<Void> ok() {
		return new ApiResponse<>(true, null, null);
	}

	public static ApiResponse<Void> fail(String message) {
		return new ApiResponse<>(false, null, message);
	}

	/** 실패하면서 화면이 써야 할 정보(남은 시도 횟수 등)를 함께 내려줄 때. */
	public static <T> ApiResponse<T> fail(String message, T data) {
		return new ApiResponse<>(false, data, message);
	}
}
