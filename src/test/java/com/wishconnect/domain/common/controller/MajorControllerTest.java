package com.wishconnect.domain.common.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wishconnect.domain.common.dto.MajorResponse;
import com.wishconnect.domain.common.entity.MajorCategory;
import com.wishconnect.domain.common.service.MajorSearchService;
import com.wishconnect.global.config.SecurityConfig;
import com.wishconnect.global.jwt.JwtAuthenticationEntryPoint;
import com.wishconnect.global.jwt.JwtProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MajorController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class})
class MajorControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private MajorSearchService majorSearchService;

	@MockBean
	private JwtProvider jwtProvider;

	@Test
	@DisplayName("전공 검색 API는 인증 없이 조회할 수 있다")
	void search() throws Exception {
		given(majorSearchService.search("컴퓨터")).willReturn(List.of(
				new MajorResponse(1L, "컴퓨터공학", MajorCategory.ENGINEERING)
		));

		mockMvc.perform(get("/api/v1/majors/search")
						.param("keyword", "컴퓨터"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].name").value("컴퓨터공학"))
				// 계열은 enum 이지만 응답에는 한글 표기로 나가야 프론트 노출값과 맞는다.
				.andExpect(jsonPath("$.data[0].category").value("공학계열"));
	}
}
