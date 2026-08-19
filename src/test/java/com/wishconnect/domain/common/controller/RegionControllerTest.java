package com.wishconnect.domain.common.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.common.repository.RegionRepository;
import com.wishconnect.global.config.SecurityConfig;
import com.wishconnect.global.jwt.JwtAuthenticationEntryPoint;
import com.wishconnect.global.jwt.JwtProvider;
import com.wishconnect.global.jwt.WithdrawnTokenStore;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RegionController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class})
class RegionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private RegionRepository regionRepository;

	@MockBean
	private JwtProvider jwtProvider;

	@MockBean
	private WithdrawnTokenStore withdrawnTokenStore;

	@Test
	@DisplayName("시군구 목록은 인증 없이 상위 시도 정보까지 반환한다")
	void getSigunguListIncludesParentRegion() throws Exception {
		Region seoul = Region.builder().name("서울").build();
		Region junggu = Region.builder().name("중구").parent(seoul).build();
		ReflectionTestUtils.setField(seoul, "id", 2L);
		ReflectionTestUtils.setField(junggu, "id", 20L);
		given(regionRepository.existsById(2L)).willReturn(true);
		given(regionRepository.findByParent_IdOrderByIdAsc(2L)).willReturn(List.of(junggu));

		mockMvc.perform(get("/api/v1/regions/2/children"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].regionId").value(20))
				.andExpect(jsonPath("$.data[0].name").value("중구"))
				.andExpect(jsonPath("$.data[0].parentId").value(2))
				.andExpect(jsonPath("$.data[0].parentName").value("서울"));
	}
}
