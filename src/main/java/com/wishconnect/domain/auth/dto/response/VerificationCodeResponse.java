package com.wishconnect.domain.auth.dto.response;

/**
 * @param sent      발송 여부
 * @param expiresIn 코드 유효시간(초)
 */
public record VerificationCodeResponse(boolean sent, long expiresIn) {
}
