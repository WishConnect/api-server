package com.wishconnect.domain.auth.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordValidatorTest {

	private static final String EMAIL = "user@example.com";

	@DisplayName("정책을 만족하는 비밀번호는 유효하다 (3종류 이상 조합, 8~20자)")
	@ParameterizedTest
	@ValueSource(strings = {"Abcd1234", "Pa$$w0rd", "aB3!aB3!aB3!", "Zz9@aaaa"})
	void validPasswords(String password) {
		assertThat(PasswordValidator.isValid(password, EMAIL)).isTrue();
	}

	@DisplayName("8자 미만은 무효")
	@ParameterizedTest
	@ValueSource(strings = {"Ab1!", "Aa1!aa"})
	void tooShort(String password) {
		assertThat(PasswordValidator.isValid(password, EMAIL)).isFalse();
	}

	@DisplayName("20자 초과는 무효")
	@ParameterizedTest
	@ValueSource(strings = {"Abcd1234Abcd1234Abcd1"})
	void tooLong(String password) {
		assertThat(PasswordValidator.isValid(password, EMAIL)).isFalse();
	}

	@DisplayName("문자 종류 2가지 이하면 무효")
	@ParameterizedTest
	@ValueSource(strings = {"abcdefgh", "abcd1234", "12345678"})
	void notEnoughCharacterTypes(String password) {
		assertThat(PasswordValidator.isValid(password, EMAIL)).isFalse();
	}

	@DisplayName("공백이 포함되면 무효")
	@ParameterizedTest
	@ValueSource(strings = {"Abcd 1234", "Ab1! efgh"})
	void containsWhitespace(String password) {
		assertThat(PasswordValidator.isValid(password, EMAIL)).isFalse();
	}

	@DisplayName("이메일과 동일한 문자열이면 무효")
	@ParameterizedTest
	@ValueSource(strings = {"user@example.com"})
	void sameAsEmail(String password) {
		assertThat(PasswordValidator.isValid(password, "user@example.com")).isFalse();
	}

	@DisplayName("null 은 무효")
	@org.junit.jupiter.api.Test
	void nullValues() {
		assertThat(PasswordValidator.isValid(null, EMAIL)).isFalse();
		assertThat(PasswordValidator.isValid("Abcd1234", null)).isFalse();
	}
}
