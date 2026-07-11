package com.wishconnect.domain.auth.dto.request;

import com.wishconnect.domain.user.entity.Gender;
import com.wishconnect.domain.user.entity.Nationality;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 기본 회원가입 요청. 기본정보 + 프로필(출생년도/성별/국적/거주지역) + 필수 약관 동의.
 */
public record SignupRequest(
		@NotBlank @Email String email,
		@NotBlank String password,
		@NotBlank String name,
		@NotBlank String phone,
		Integer birthYear,
		@NotNull Gender gender,
		Nationality nationality,
		String region,
		@NotEmpty @Valid List<AgreementItem> agreements
) {
}
