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
	/** 로그인은 했지만 권한이 모자란 경우. 인증 실패(401)와 구분해야 프론트가 재로그인을 시키지 않는다. */
	FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다."),
	WITHDRAWN_USER(HttpStatus.UNAUTHORIZED, "탈퇴한 계정입니다. 다시 로그인해주세요."),
	/** 매핑된 엔드포인트가 없는 경로. 리소스 하나가 없는 경우(~_NOT_FOUND)와 구분한다. */
	ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "요청하신 경로를 찾을 수 없습니다."),
	/** 경로는 있으나 HTTP 메서드가 다른 경우(예: GET 전용 경로에 POST). */
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 메서드입니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

	// 회원가입 / 로그인
	DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
	DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
	/** 영문 소문자·숫자·언더스코어 4~20자. 이메일과 헷갈리지 않도록 @ 와 점은 막는다. */
	INVALID_LOGIN_ID_FORMAT(HttpStatus.BAD_REQUEST, "아이디는 영문 소문자·숫자·_ 4~20자여야 합니다."),
	INVALID_PASSWORD_FORMAT(HttpStatus.BAD_REQUEST, "비밀번호 형식이 올바르지 않습니다."),
	AGREEMENT_REQUIRED(HttpStatus.BAD_REQUEST, "필수 약관에 모두 동의해주세요."),
	/** 온보딩 STEP2 전공 계열. 대학알리미 대계열 6종만 허용한다. */
	INVALID_MAJOR_CATEGORY(HttpStatus.BAD_REQUEST, "지원하지 않는 전공 계열입니다."),
	/** 거주지역을 특정하지 못한 경우. 시군구 이름은 여러 시도에 중복되므로 "서울 중구" 형태로 보내야 한다. */
	INVALID_REGION(HttpStatus.BAD_REQUEST, "거주지역을 찾을 수 없습니다. 시도와 함께 선택해주세요."),
	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 일치하지 않습니다."),
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
	/**
	 * 응답이 max_tokens 에서 잘렸다. 같은 요청은 같은 지점에서 다시 잘리므로
	 * <b>재시도해도 소용없다</b> — 호출측은 재시도 대상에서 빼고 max_tokens 를 올려야 한다.
	 */
	LLM_RESPONSE_TRUNCATED(HttpStatus.BAD_GATEWAY, "AI 응답이 길이 제한에서 잘렸습니다."),

	// 장학금/지원서
	SCHOLARSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 장학금입니다."),
	/** 수기 등록·수정에서 마감이 시작보다 앞설 때. 모집 상태 계산이 뒤틀리므로 입력 단계에서 막는다. */
	INVALID_APPLICATION_PERIOD(HttpStatus.BAD_REQUEST, "모집 종료일이 시작일보다 빠를 수 없습니다."),
	REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 신고입니다."),
	REPORT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 접수된 신고가 처리 중입니다."),
	CONTENT_INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 콘텐츠 문의입니다."),
	INQUIRY_ATTACHMENT_TOO_LARGE(HttpStatus.BAD_REQUEST, "첨부파일은 2MB 이하로 올려주세요."),
	INQUIRY_ATTACHMENT_INVALID_FORMAT(HttpStatus.BAD_REQUEST, "PDF, PNG, JPG, JPEG 파일만 첨부할 수 있습니다."),
	INQUIRY_ATTACHMENT_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "첨부파일 저장에 실패했습니다."),
	ADMIN_IMAGE_SAVE_FAILED(HttpStatus.BAD_GATEWAY, "이미지를 저장하지 못했습니다. URL 또는 파일 형식을 확인해주세요."),
	APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 지원서입니다."),
	APPLICATION_ALREADY_EXISTS(HttpStatus.CONFLICT, "해당 장학금에 대한 지원서가 이미 존재합니다."),
	ONBOARDING_INCOMPLETE(HttpStatus.BAD_REQUEST, "이전 단계를 먼저 완료해주세요."),
	QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 지원서에 존재하지 않는 문항입니다."),
	INVALID_INTERVIEW_STEP(HttpStatus.BAD_REQUEST, "현재 인터뷰 진행 상태와 맞지 않는 요청입니다."),
	ANSWER_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 문항의 답변 레코드를 찾을 수 없습니다."),
	INTERVIEW_NOT_STARTED(HttpStatus.BAD_REQUEST, "사전 인터뷰가 시작되지 않아 초안을 생성할 수 없습니다."),
	/** LLM 이 사전 질문을 하나도 만들지 못한 경우. 재시도하면 대개 해소되므로 사용자에게 재시도를 안내한다. */
	INTERVIEW_QUESTION_GENERATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE,
			"사전 질문을 생성하지 못했습니다. 잠시 후 다시 시도해주세요."),
	/**
	 * 공고가 자기소개서를 요구하지 않는 장학금에 지원서를 만들려 한 경우.
	 *
	 * <p>사용자 잘못이 아니라 화면 분기 문제이므로, 프론트는 이 코드를 받으면 지원서 작성 대신
	 * 신청 홈페이지로 안내해야 한다. {@code essayRequirement} 가 NOT_REQUIRED 일 때만 발생하며,
	 * null(공고에 언급 없음)은 판단 불가라 막지 않는다.
	 */
	ESSAY_NOT_REQUIRED(HttpStatus.BAD_REQUEST,
			"이 장학금은 자기소개서를 요구하지 않습니다. 신청 홈페이지에서 바로 지원하세요."),
	/** 공고가 면접을 보지 않는다고 밝힌 장학금에 면접 예상 질문을 요청한 경우. */
	INTERVIEW_NOT_REQUIRED(HttpStatus.BAD_REQUEST,
			"이 장학금은 면접을 진행하지 않습니다."),
	/** LLM 이 면접 예상 질문을 하나도 만들지 못한 경우. 재시도하면 대개 해소된다. */
	INTERVIEW_PREP_GENERATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE,
			"면접 예상 질문을 생성하지 못했습니다. 잠시 후 다시 시도해주세요."),
	/**
	 * 한 사용자가 짧은 시간에 너무 많은 장학금의 질문을 새로 만들려 한 경우.
	 *
	 * <p>생성은 LLM 크레딧을 쓰므로, 장학금 ID 를 순회하며 호출하면 비용이 그대로 나간다.
	 * <b>이미 만들어진 질문 조회는 이 제한에 걸리지 않는다.</b>
	 */
	INTERVIEW_PREP_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS,
			"면접 예상 질문 생성 한도를 초과했습니다. 잠시 후 다시 시도해주세요."),
	/** 마감된 공고. 준비할 면접이 없어 생성 대상에서 뺀다. */
	INTERVIEW_PREP_CLOSED_SCHOLARSHIP(HttpStatus.BAD_REQUEST,
			"마감된 장학금입니다."),
	/**
	 * 이미 작성을 시작해 문항을 바꿀 수 없는 경우.
	 *
	 * <p>문항을 교체하려면 그 문항에 딸린 답변과 사전 인터뷰를 지워야 한다. 학생이 쓴 글을
	 * 없애는 것은 어떤 맞춤 문항보다 나쁘므로, 작성 전에만 허용한다.
	 */
	ESSAY_QUESTIONS_LOCKED(HttpStatus.CONFLICT,
			"이미 작성을 시작해 문항을 변경할 수 없습니다."),
	/** 한 사용자가 짧은 시간에 너무 많은 지원서의 문항을 생성하려 한 경우. */
	ESSAY_QUESTION_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS,
			"문항 생성 한도를 초과했습니다. 잠시 후 다시 시도해주세요."),
	ANSWER_EXCEEDS_CHAR_LIMIT(HttpStatus.BAD_REQUEST, "답변이 글자수 제한을 초과했습니다."),
	ANSWER_CONTENT_REQUIRED(HttpStatus.BAD_REQUEST, "답변 본문이 필요합니다."),

	// 관리자 엑셀 일괄 편집
	EXCEL_FILE_REQUIRED(HttpStatus.BAD_REQUEST, "엑셀 파일을 첨부해주세요."),
	EXCEL_FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "파일이 너무 큽니다. 1MB 이하로 올려주세요."),
	EXCEL_INVALID_FORMAT(HttpStatus.BAD_REQUEST, "xlsx 형식만 업로드할 수 있습니다."),
	EXCEL_PARSE_FAILED(HttpStatus.BAD_REQUEST, "엑셀 파일을 읽지 못했습니다. 내보내기 받은 양식인지 확인해주세요."),
	EXCEL_TOO_MANY_ROWS(HttpStatus.BAD_REQUEST, "한 번에 처리할 수 있는 행 수를 초과했습니다."),
	/** 내보내기 파일에는 ID 가 채워져 있다. 비어 있으면 사람이 새로 추가한 행이다. */
	EXCEL_ROW_ID_REQUIRED(HttpStatus.BAD_REQUEST, "ID 가 비어 있습니다. 신규 등록은 수기 등록 기능을 이용해주세요."),
	EXCEL_INVALID_NUMBER(HttpStatus.BAD_REQUEST, "숫자 칸에 숫자가 아닌 값이 있습니다."),
	EXCEL_INVALID_DATE(HttpStatus.BAD_REQUEST, "날짜 형식이 올바르지 않습니다. (yyyy-MM-dd HH:mm)"),

	// 이메일 인증
	EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증을 먼저 완료해주세요."),
	EMAIL_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이메일 발송에 실패했습니다."),
	INVALID_VERIFICATION_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 올바르지 않습니다."),
	VERIFICATION_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증 코드가 만료되었습니다. 다시 요청해주세요."),
	ACCOUNT_RECOVERY_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST,
			"인증 코드가 올바르지 않거나 만료되었습니다. 다시 요청해주세요."),
	PASSWORD_RESET_TOKEN_INVALID(HttpStatus.BAD_REQUEST,
			"비밀번호 재설정 인증이 만료되었습니다. 다시 요청해주세요."),
	TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 잦습니다. 잠시 후 다시 시도해주세요."),

	// 아카이빙(스크랩)
	ALREADY_SCRAPPED(HttpStatus.CONFLICT, "이미 스크랩한 장학금입니다."),
	SCRAP_NOT_FOUND(HttpStatus.NOT_FOUND, "스크랩하지 않은 장학금입니다."),
	INVALID_ARCHIVE_STATUS(HttpStatus.BAD_REQUEST, "지원하지 않는 상태 필터입니다."),

	// 알림
	NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 알림입니다."),

	//검색
	INVALID_SORT(HttpStatus.BAD_REQUEST,"지원하지 않는 정렬 기준입니다."),
	INVALID_CATEGORY(HttpStatus.BAD_REQUEST, "지원하지 않는 장학금 분류입니다."),
	LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),

	//인사이트
	INVALID_INSIGHT_INPUT(HttpStatus.BAD_REQUEST, "지원하지 않는 카테고리/출처입니다."),
	/**
	 * 네이버 검색 API 호출 자체가 실패(401·쿼터 초과·타임아웃 등).
	 * "검색은 됐는데 결과가 0건" 과 반드시 구분한다 — 예전에는 실패를 빈 결과로 삼켜서,
	 * 키가 401 로 죽어 있는데도 "검색 결과 없음" 으로 보였다.
	 */
	NAVER_SEARCH_FAILED(HttpStatus.BAD_GATEWAY, "네이버 검색 API 호출에 실패했습니다.");


	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}
}
