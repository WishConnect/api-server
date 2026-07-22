package com.wishconnect.domain.common.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wishconnect.domain.common.dto.UniversityResponse;
import com.wishconnect.domain.common.service.AcademicInfoSyncService;
import com.wishconnect.domain.common.service.UniversitySearchService;
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

@WebMvcTest(UniversityController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class})
class UniversityControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private UniversitySearchService universitySearchService;

	@MockBean
	private AcademicInfoSyncService academicInfoSyncService;

	@MockBean
	private JwtProvider jwtProvider;

	@Test
	@DisplayName("학교 검색 API는 명세 경로로 인증 없이 조회할 수 있다")
	void search() throws Exception {
		given(universitySearchService.search("건국")).willReturn(List.of(
				new UniversityResponse(1L, "건국대학교", "서울")
		));

		mockMvc.perform(get("/api/v1/universities/search")
						.param("keyword", "건국"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].name").value("건국대학교"));
	}
}
