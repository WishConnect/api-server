package com.wishconnect.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 도메인 전역에서 사용하는 에러 코드.
 * 노션 'Auth (인증/회원)' API 명세서의 상태코드/메시지를 그대로 따른다.
 */
@Getter
public enum ErrorCode {

	// 공통
	INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

	// 회원가입 / 로그인
	DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
	INVALID_PASSWORD_FORMAT(HttpStatus.BAD_REQUEST, "비밀번호 형식이 올바르지 않습니다."),
	AGREEMENT_REQUIRED(HttpStatus.BAD_REQUEST, "필수 약관에 모두 동의해주세요."),
	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다."),

	// 카카오 소셜로그인
	INVALID_KAKAO_CODE(HttpStatus.BAD_REQUEST, "인가 코드가 존재하지 않습니다. 다시 시도해주세요."),
	KAKAO_TOKEN_FAILED(HttpStatus.UNAUTHORIZED, "유효하지 않은 소셜 로그인 토큰입니다."),
	KAKAO_USER_INFO_FAILED(HttpStatus.BAD_GATEWAY, "카카오 사용자 정보를 가져오지 못했습니다."),

	// 구글 소셜로그인
	INVALID_GOOGLE_CODE(HttpStatus.BAD_REQUEST, "인가 코드가 존재하지 않습니다. 다시 시도해주세요."),
	GOOGLE_TOKEN_FAILED(HttpStatus.UNAUTHORIZED, "유효하지 않은 소셜 로그인 토큰입니다."),
	GOOGLE_USER_INFO_FAILED(HttpStatus.BAD_GATEWAY, "구글 사용자 정보를 가져오지 못했습니다."),

	// 네이버 소셜로그인
	INVALID_NAVER_CODE(HttpStatus.BAD_REQUEST, "인가 코드가 존재하지 않습니다. 다시 시도해주세요."),
	INVALID_NAVER_STATE(HttpStatus.BAD_REQUEST, "잘못된 접근입니다. (state 검증 실패)"),
	NAVER_TOKEN_FAILED(HttpStatus.UNAUTHORIZED, "유효하지 않은 소셜 로그인 토큰입니다."),
	NAVER_USER_INFO_FAILED(HttpStatus.BAD_GATEWAY, "네이버 사용자 정보를 가져오지 못했습니다."),

	// 토큰
	INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
	TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "만료되었거나 존재하지 않는 토큰입니다. 다시 로그인해주세요."),

	// LLM (AI 자기소개서)
	LLM_CALL_FAILED(HttpStatus.BAD_GATEWAY, "AI 응답 생성에 실패했습니다. 잠시 후 다시 시도해주세요."),
	LLM_EMPTY_RESPONSE(HttpStatus.BAD_GATEWAY, "AI 응답이 비어 있습니다. 잠시 후 다시 시도해주세요."),

	// 이메일 인증
	EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증을 먼저 완료해주세요."),
	EMAIL_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이메일 발송에 실패했습니다."),
	INVALID_VERIFICATION_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 올바르지 않습니다."),
	VERIFICATION_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증 코드가 만료되었습니다. 다시 요청해주세요."),
	TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 잦습니다. 잠시 후 다시 시도해주세요."),

	// 장학금 (큐레이팅/상세)
	SCHOLARSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 장학금입니다.");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}
}
