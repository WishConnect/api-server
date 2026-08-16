package com.wishconnect.domain.auth.dto.response;

/** 아이디 중복 확인 결과. available=true 면 사용할 수 있다. */
public record LoginIdCheckResponse(boolean available) {
}
