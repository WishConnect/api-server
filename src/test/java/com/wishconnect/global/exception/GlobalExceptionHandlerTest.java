package com.wishconnect.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wishconnect.global.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 클라이언트 입력 오류가 500이 아닌 400으로 나가는지 검증한다.
 * (FE가 필드를 잘못 보냈을 때 "서버 내부 오류"로 응답하던 회귀 방지)
 */
class GlobalExceptionHandlerTest {

	private final MockMvc mockMvc = MockMvcBuilders
			.standaloneSetup(new TestController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();

	@Test
	@DisplayName("JSON 문법이 깨진 요청 바디는 400을 반환한다")
	void malformedJsonReturns400() throws Exception {
		mockMvc.perform(post("/test/body")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.message").value(ErrorCode.INVALID_INPUT.getMessage()));
	}

	@Test
	@DisplayName("필드 타입이 맞지 않는 요청 바디는 400을 반환한다")
	void wrongFieldTypeReturns400() throws Exception {
		mockMvc.perform(post("/test/body")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": {\"nested\": true}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false));
	}

	@Test
	@DisplayName("요청 바디가 아예 없으면 400을 반환한다")
	void missingBodyReturns400() throws Exception {
		mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("쿼리 파라미터 타입이 맞지 않으면 400을 반환한다")
	void parameterTypeMismatchReturns400() throws Exception {
		mockMvc.perform(get("/test/param").param("page", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(ErrorCode.INVALID_INPUT.getMessage()));
	}

	@Test
	@DisplayName("필수 쿼리 파라미터가 없으면 400을 반환한다")
	void missingParameterReturns400() throws Exception {
		mockMvc.perform(get("/test/required"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("경로는 있지만 HTTP 메서드가 다르면 405를 반환한다")
	void wrongHttpMethodReturns405() throws Exception {
		mockMvc.perform(delete("/test/body"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.message").value(ErrorCode.METHOD_NOT_ALLOWED.getMessage()));
	}

	@Test
	@DisplayName("405 응답에는 허용 메서드를 알리는 Allow 헤더가 붙는다")
	void methodNotAllowedIncludesAllowHeader() throws Exception {
		mockMvc.perform(delete("/test/body"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string("Allow", "POST"));
	}

	/**
	 * 매핑되지 않은 경로는 NoResourceFoundException 으로 올라온다. standalone MockMvc 에는
	 * 이 예외를 던지는 리소스 핸들러가 없어 실제 요청으로는 재현되지 않으므로 핸들러를 직접 호출해 검증한다.
	 */
	@Test
	@DisplayName("매핑되지 않은 경로는 500이 아니라 404를 반환한다")
	void unmappedPathReturns404() {
		ResponseEntity<ApiResponse<Void>> response = new GlobalExceptionHandler()
				.handleNoResourceFoundException(
						new NoResourceFoundException(HttpMethod.GET, "/api/v1/does-not-exist"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().success()).isFalse();
		assertThat(response.getBody().message()).isEqualTo(ErrorCode.ENDPOINT_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("404 메시지는 서버 오류가 아니라 경로 문제임을 알린다")
	void notFoundMessageIsDistinguishableFromServerError() {
		assertThat(ErrorCode.ENDPOINT_NOT_FOUND.getMessage())
				.isNotEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
	}

	@RestController
	static class TestController {

		record Body(String name) {
		}

		@PostMapping("/test/body")
		ApiResponse<Void> body(@RequestBody Body body) {
			return ApiResponse.ok();
		}

		@GetMapping("/test/param")
		ApiResponse<Void> param(@RequestParam(defaultValue = "1") int page) {
			return ApiResponse.ok();
		}

		@GetMapping("/test/required")
		ApiResponse<Void> required(@RequestParam String keyword) {
			return ApiResponse.ok();
		}
	}
}
