package com.wishconnect.domain.user.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wishconnect.domain.user.dto.response.OnboardingCompleteResponse;
import com.wishconnect.domain.user.dto.response.ProfileResponse;
import com.wishconnect.domain.user.service.UserProfileService;
import com.wishconnect.global.config.SecurityConfig;
import com.wishconnect.global.jwt.JwtAuthenticationEntryPoint;
import com.wishconnect.global.jwt.JwtProvider;
import com.wishconnect.global.jwt.WithdrawnTokenStore;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserProfileController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class})
class UserProfileControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private UserProfileService userProfileService;

	@MockBean
	private JwtProvider jwtProvider;

	/** SecurityConfig 가 JwtAuthenticationFilter 에 넘기는 협력자. 슬라이스에는 Redis 가 없다. */
	@MockBean
	private WithdrawnTokenStore withdrawnTokenStore;

	@Test
	@DisplayName("프로필 조회 시 시군구를 상위 시도와 함께 반환한다")
	void getProfileIncludesParentRegion() throws Exception {
		UUID userId = UUID.randomUUID();
		ProfileResponse response = new ProfileResponse(
				userId,
				"김위시",
				"wish@example.com",
				null,
				null,
				null,
				null,
				"서울 중구",
				50,
				false,
				"STEP_1",
				null,
				null,
				java.util.List.of());
		given(jwtProvider.validateToken("valid-token")).willReturn(true);
		given(jwtProvider.getUserId("valid-token")).willReturn(userId);
		given(userProfileService.getProfile(userId)).willReturn(response);

		mockMvc.perform(get("/api/v1/users/me/profile")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.region").value("서울 중구"));
	}

	@Test
	@DisplayName("온보딩 완료 시 추천 job id 없이 완료 여부만 반환한다")
	void complete() throws Exception {
		UUID userId = UUID.randomUUID();
		given(jwtProvider.validateToken("valid-token")).willReturn(true);
		given(jwtProvider.getUserId("valid-token")).willReturn(userId);
		given(userProfileService.complete(userId)).willReturn(new OnboardingCompleteResponse(true));

		mockMvc.perform(post("/api/v1/users/me/profile/complete")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.onboardingCompleted").value(true))
				.andExpect(jsonPath("$.data.recommendationJobId").doesNotExist());
	}
}
