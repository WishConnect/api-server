package com.wishconnect.domain.scholarship.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wishconnect.domain.scholarship.service.ScholarshipPipelineReviewService;
import com.wishconnect.global.config.SecurityConfig;
import com.wishconnect.global.jwt.JwtAuthenticationEntryPoint;
import com.wishconnect.global.jwt.JwtProvider;
import com.wishconnect.global.jwt.WithdrawnTokenStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = ScholarshipPipelineReviewController.class,
        properties = "scholarship.pipeline.review-enabled=true")
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class})
class ScholarshipPipelineReviewControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private ScholarshipPipelineReviewService service;
    @MockBean private JwtProvider jwtProvider;
    @MockBean private WithdrawnTokenStore withdrawnTokenStore;
    private static final String PATH = "/api/v1/admin/scholarship-pipeline/compare";

    @Test
    void rejectsGuestAndRegularUserBeforePaidCall() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"rawIds\":[1]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).with(user("member").roles("USER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"rawIds\":[1]}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void validatesBatchLimitAndNullIdBeforePaidCall() throws Exception {
        for (String body : new String[]{"{\"rawIds\":[null]}",
                "{\"rawIds\":[1,2,3,4,5,6,7,8,9,10,11]}", "{\"rawIds\":[]}"}) {
            mvc.perform(post(PATH).with(user("admin").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
