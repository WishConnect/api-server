package com.wishconnect.domain.scholarship.controller;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wishconnect.domain.scholarship.service.ScholarshipCalendarService;
import com.wishconnect.domain.scholarship.service.ScholarshipDetailService;
import com.wishconnect.domain.scholarship.service.ScholarshipEventService;
import com.wishconnect.domain.scholarship.service.ScholarshipRecommendationService;
import com.wishconnect.domain.scholarship.service.ScholarshipService;
import com.wishconnect.global.config.SecurityConfig;
import com.wishconnect.global.jwt.JwtAuthenticationEntryPoint;
import com.wishconnect.global.jwt.JwtProvider;
import com.wishconnect.global.jwt.WithdrawnTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ScholarshipController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class})
class ScholarshipControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private ScholarshipRecommendationService scholarshipRecommendationService;

	@MockBean
	private ScholarshipDetailService scholarshipDetailService;

	@MockBean
	private ScholarshipCalendarService scholarshipCalendarService;

	@MockBean
	private ScholarshipService scholarshipService;

	@MockBean
	private ScholarshipEventService scholarshipEventService;

	@MockBean
	private JwtProvider jwtProvider;

	@MockBean
	private WithdrawnTokenStore withdrawnTokenStore;

	@Test
	@DisplayName("비로그인은 토큰 없이 장학금 상세를 조회한다")
	void guestCanGetScholarshipDetail() throws Exception {
		given(scholarshipDetailService.getDetail(isNull(), org.mockito.ArgumentMatchers.eq(1L)))
				.willReturn(null);

		mockMvc.perform(get("/api/v1/scholarships/1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

		verify(scholarshipDetailService).getDetail(isNull(), org.mockito.ArgumentMatchers.eq(1L));
	}
}
