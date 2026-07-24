package com.wishconnect.domain.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailUpdateRequest(
		@NotBlank @Email String email
) {
}
