package com.wishconnect.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wishconnect.global.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
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
