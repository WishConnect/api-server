package com.wishconnect.domain.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wishconnect.domain.user.entity.AgreementType;
import jakarta.validation.constraints.NotNull;

/**
 * 약관 동의 항목.
 */
public record AgreementItem(
		@NotNull AgreementType type,
		@JsonProperty("isAgreed") boolean isAgreed
) {
}
