package com.wishconnect.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@DisplayName("업로드 한도 초과 응답")
class GlobalExceptionHandlerUploadTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	@DisplayName("multipart 한도 초과는 500 이 아니라 413 과 한국어 안내다")
	void maxUploadSizeIs413() {
		var response = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(5L * 1024 * 1024));

		assertThat(response.getStatusCode().value()).isEqualTo(413);
		assertThat(response.getBody().success()).isFalse();
		assertThat(response.getBody().message()).contains("5MB");
	}

	@Test
	@DisplayName("원인이 있는 5xx CustomException 도 같은 형식으로 응답한다")
	void customExceptionWithCause() {
		var response = handler.handleCustomException(
				new CustomException(ErrorCode.MERGE_FAILED, new IllegalStateException("boom")));

		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody().message()).isEqualTo(ErrorCode.MERGE_FAILED.getMessage());
	}
}
