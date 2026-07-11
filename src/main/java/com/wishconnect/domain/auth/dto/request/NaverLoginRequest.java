package com.wishconnect.domain.auth.dto.request;

/**
 * 네이버 인가코드 + state. 빈 값 검증은 서비스에서 INVALID_NAVER_CODE / INVALID_NAVER_STATE 로 처리한다.
 */
public record NaverLoginRequest(String code, String state) {
}
