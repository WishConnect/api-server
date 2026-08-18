package com.wishconnect.domain.auth.util;

import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** 로그인 아이디를 모든 인증 흐름에서 같은 기준으로 정규화하고 검증한다. */
public final class LoginIdNormalizer {

	private static final Pattern LOGIN_ID_PATTERN = Pattern.compile("^[a-z0-9_]{4,20}$");

	private LoginIdNormalizer() {
	}

	public static String normalize(String value) {
		if (!StringUtils.hasText(value)) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (!LOGIN_ID_PATTERN.matcher(normalized).matches()) {
			throw new CustomException(ErrorCode.INVALID_LOGIN_ID_FORMAT);
		}
		return normalized;
	}
}
