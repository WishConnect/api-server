package com.wishconnect.domain.application.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.UnauthorizedException;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import com.wishconnect.global.operation.AdminJobFailureType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LLM 실패 유형 분류")
class LlmFailureClassifierTest {

	@Test
	@DisplayName("크레딧 부족 문구가 원인에 있으면 LLM_CREDIT")
	void creditExhausted() {
		CustomException wrapped = new CustomException(ErrorCode.LLM_CALL_FAILED, new IllegalStateException(
				"400: Your credit balance is too low to access the Anthropic API."));

		assertThat(LlmFailureClassifier.classify(wrapped)).isEqualTo(AdminJobFailureType.LLM_CREDIT);
		assertThat(LlmFailureClassifier.prefix(AdminJobFailureType.LLM_CREDIT)).contains("크레딧");
	}

	@Test
	@DisplayName("401 Unauthorized 는 LLM_AUTH")
	void unauthorized() {
		UnauthorizedException unauthorized = UnauthorizedException.builder()
				.headers(Headers.builder().build())
				.body(JsonValue.from(Map.of("error", Map.of("type", "authentication_error"))))
				.build();

		assertThat(LlmFailureClassifier.classify(new CustomException(ErrorCode.LLM_CALL_FAILED, unauthorized)))
				.isEqualTo(AdminJobFailureType.LLM_AUTH);
	}

	@Test
	@DisplayName("그 밖의 LLM 오류는 LLM_ERROR, LLM 과 무관하면 null")
	void otherErrors() {
		assertThat(LlmFailureClassifier.classify(new CustomException(ErrorCode.LLM_CALL_FAILED,
				new java.net.SocketTimeoutException("timeout")))).isEqualTo(AdminJobFailureType.LLM_ERROR);
		assertThat(LlmFailureClassifier.classify(new CustomException(ErrorCode.LLM_RESPONSE_TRUNCATED)))
				.isEqualTo(AdminJobFailureType.LLM_ERROR);
		assertThat(LlmFailureClassifier.classify(new IllegalArgumentException("db"))).isNull();
	}
}
