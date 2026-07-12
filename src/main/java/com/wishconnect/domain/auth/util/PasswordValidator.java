package com.wishconnect.domain.auth.util;

/**
 * 비밀번호 정책 검증 (LOCAL 가입).
 * <ul>
 *   <li>길이: 8~20자</li>
 *   <li>영문 대문자/소문자/숫자/특수문자 중 3종류 이상 조합</li>
 *   <li>공백 불가</li>
 *   <li>이메일과 동일한 문자열 불가</li>
 * </ul>
 */
public final class PasswordValidator {

	private static final int MIN_LENGTH = 8;
	private static final int MAX_LENGTH = 20;
	private static final int MIN_CHARACTER_TYPES = 3;

	private PasswordValidator() {
	}

	public static boolean isValid(String password, String email) {
		if (password == null || email == null) {
			return false;
		}
		if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
			return false;
		}
		if (containsWhitespace(password)) {
			return false;
		}
		if (password.equals(email)) {
			return false;
		}
		return countCharacterTypes(password) >= MIN_CHARACTER_TYPES;
	}

	private static boolean containsWhitespace(String value) {
		for (char c : value.toCharArray()) {
			if (Character.isWhitespace(c)) {
				return true;
			}
		}
		return false;
	}

	private static int countCharacterTypes(String password) {
		boolean hasUpper = false;
		boolean hasLower = false;
		boolean hasDigit = false;
		boolean hasSpecial = false;
		for (char c : password.toCharArray()) {
			if (Character.isUpperCase(c)) {
				hasUpper = true;
			} else if (Character.isLowerCase(c)) {
				hasLower = true;
			} else if (Character.isDigit(c)) {
				hasDigit = true;
			} else {
				hasSpecial = true;
			}
		}
		int types = 0;
		if (hasUpper) {
			types++;
		}
		if (hasLower) {
			types++;
		}
		if (hasDigit) {
			types++;
		}
		if (hasSpecial) {
			types++;
		}
		return types;
	}
}
