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
	/** 온보딩 STEP2 전공 계열. 대학알리미 대계열 6종만 허용한다. */
	INVALID_MAJOR_CATEGORY(HttpStatus.BAD_REQUEST, "지원하지 않는 전공 계열입니다."),
	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다."),
	/** 소셜 3사 공통: 프론트가 보낸 redirectUri 가 서버 허용목록에 없을 때. */
	INVALID_REDIRECT_URI(HttpStatus.BAD_REQUEST, "허용되지 않은 redirectUri 입니다."),

	// 카카오 소셜로그인
	INVALID_KAKAO_CODE(HttpStatus.BAD_REQUEST, "인가 코드가 존재하지 않습니다. 다시 시도해주세요."),
	KAKAO_TOKEN_FAILED(HttpStatus.UNAUTHORIZED, "유효하지 않은 소셜 로그인 토큰입니다."),
	KAKAO_USER_INFO_FAILED(HttpStatus.BAD_GATEWAY, "카카오 사용자 정보를 가져오지 못했습니다."),
	/** 비즈 앱 전환으로 이메일이 필수 동의항목이 됐다. 못 받으면 알림 메일이 안 나가므로 가입을 막는다. */
	KAKAO_EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "카카오 계정 이메일 제공에 동의해야 가입할 수 있습니다."),

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

	// 장학금/지원서
	SCHOLARSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 장학금입니다."),
	APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 지원서입니다."),
	APPLICATION_ALREADY_EXISTS(HttpStatus.CONFLICT, "해당 장학금에 대한 지원서가 이미 존재합니다."),
	ONBOARDING_INCOMPLETE(HttpStatus.BAD_REQUEST, "이전 단계를 먼저 완료해주세요."),
	QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 지원서에 존재하지 않는 문항입니다."),
	INVALID_INTERVIEW_STEP(HttpStatus.BAD_REQUEST, "현재 인터뷰 진행 상태와 맞지 않는 요청입니다."),
	ANSWER_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 문항의 답변 레코드를 찾을 수 없습니다."),
	INTERVIEW_NOT_STARTED(HttpStatus.BAD_REQUEST, "사전 인터뷰가 시작되지 않아 초안을 생성할 수 없습니다."),
	ANSWER_EXCEEDS_CHAR_LIMIT(HttpStatus.BAD_REQUEST, "답변이 글자수 제한을 초과했습니다."),
	ANSWER_CONTENT_REQUIRED(HttpStatus.BAD_REQUEST, "답변 본문이 필요합니다."),

	// 이메일 인증
	EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증을 먼저 완료해주세요."),
	EMAIL_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이메일 발송에 실패했습니다."),
	INVALID_VERIFICATION_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 올바르지 않습니다."),
	VERIFICATION_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증 코드가 만료되었습니다. 다시 요청해주세요."),
	TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 잦습니다. 잠시 후 다시 시도해주세요."),

	// 아카이빙(스크랩)
	ALREADY_SCRAPPED(HttpStatus.CONFLICT, "이미 스크랩한 장학금입니다."),
	SCRAP_NOT_FOUND(HttpStatus.NOT_FOUND, "스크랩하지 않은 장학금입니다."),

	// 알림
	NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 알림입니다."),

	//검색
	INVALID_SORT(HttpStatus.BAD_REQUEST,"지원하지 않는 정렬 기준입니다."),
	INVALID_CATEGORY(HttpStatus.BAD_REQUEST, "지원하지 않는 장학금 분류입니다."),
	LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),

	//인사이트
	INVALID_INSIGHT_INPUT(HttpStatus.BAD_REQUEST, "지원하지 않는 카테고리/출처입니다.");


	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}
}
