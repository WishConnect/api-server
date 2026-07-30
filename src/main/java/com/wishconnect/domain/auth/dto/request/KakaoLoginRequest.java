package com.wishconnect.domain.auth.dto.request;

/**
 * 카카오 인가코드. 빈 값 검증은 명세에 따라 서비스에서 INVALID_KAKAO_CODE 로 처리한다.
 */
public record KakaoLoginRequest(String code, String redirectUri) {
}
