package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.config.LlmProperties;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipPipelineRun;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipPipelineRunRepository;
import com.wishconnect.domain.scholarship.util.TemporalCandidateParser;
import com.wishconnect.domain.scholarship.util.UnivNoticeLlmParser;
import com.wishconnect.global.exception.CustomException;
import java.time.LocalDateTime;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ScholarshipPipelineReviewServiceTest {
    private final RawScholarshipRepository rawRepository = mock(RawScholarshipRepository.class);
    private final ScholarshipPipelineRunRepository runRepository = mock(ScholarshipPipelineRunRepository.class);
    private final LlmClient client = mock(LlmClient.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ScholarshipPipelineReviewService service = new ScholarshipPipelineReviewService(
            rawRepository, runRepository, new UnivNoticeLlmParser(mapper), new TemporalCandidateParser(), client,
            new LlmProperties(null, null, null, "test-model", 100), mapper);
    private Scholarship scholarship;
    private RawScholarship raw;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "enabled", true);
        scholarship = Scholarship.builder().title("기존 장학금")
                .applicationEndAt(LocalDateTime.of(2026, 12, 31, 18, 0)).build();
        ReflectionTestUtils.setField(scholarship, "id", 10L);
        scholarship.lockTimeline();
        raw = RawScholarship.builder().source("UNIV_TEST").sourceId("1").scholarship(scholarship)
                .sourceUrl("https://example.edu/1")
                .rawHtml("<h2 class='view-title'>장학생 모집(2026.11.18 ~ 11.26)</h2>"
                        + "<article>이 공고의 자세한 장학금 지원 자격과 신청서 제출 방법은 첨부파일을 확인해주세요.</article>")
                .build();
        ReflectionTestUtils.setField(raw, "id", 1L);
        when(rawRepository.findPipelineInputs(List.of(1L))).thenReturn(List.of(raw));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void comparesOneResponseAndDoesNotChangeCanonicalEntities() {
        String response = "{\"timeline\":[]}";
        when(client.chat(any())).thenReturn(response);
        ScholarshipPipelineRun run = service.compareRaw(List.of(1L)).get(0);
        assertThat(run.getStatus()).isEqualTo("COMPARED");
        assertThat(run.getCandidateJson()).contains("2026-11-26", "DATE_ONLY");
        assertThat(run.getBaselineJson()).contains("LEGACY_VALIDATION_SAME_MODEL_RESPONSE", "\"period\":null");
        assertThat(run.getInputJson()).contains("rawHtml", "장학생 모집");
        assertThat(run.getResponseJson()).isEqualTo(response);
        assertThat(run.getInputHash()).hasSize(64);
        assertThat(scholarship.getApplicationEndAt()).isEqualTo(LocalDateTime.of(2026, 12, 31, 18, 0));
        assertThat(scholarship.isTimelineLocked()).isTrue();
        verify(client, times(1)).chat(any());
        verify(rawRepository, never()).save(any());
    }

    @Test
    void offlineReplayHasExplicitProvenanceAndNoPaidCalls() {
        var run = service.replayRaw(1L, "{\"timeline\":[]}");
        assertThat(run.getStatus()).isEqualTo("COMPARED");
        assertThat(run.getModelId()).isEqualTo("provided-response");
        assertThat(run.getCandidateJson()).contains("2026-11-26");
        verifyNoInteractions(client);
    }

    @Test
    void recordsModelFailureWithoutSecretExceptionText() {
        when(client.chat(any())).thenThrow(new IllegalStateException("secret-example-key"));
        var run = service.compareRaw(List.of(1L)).get(0);
        assertThat(run.getStatus()).isEqualTo("LLM_FAILED");
        assertThat(run.getErrorMessage()).isEqualTo("IllegalStateException");
        assertThat(run.getInputJson()).doesNotContain("secret-example-key");
    }

    @Test
    void recordsInvalidResponseForLaterInvestigation() {
        when(client.chat(any())).thenReturn("not-json");
        var run = service.compareRaw(List.of(1L)).get(0);
        assertThat(run.getStatus()).isEqualTo("INVALID_RESPONSE");
        assertThat(run.getResponseJson()).isEqualTo("not-json");
    }

    @Test
    void missingOrDismissedTargetsDoNotCausePaidCalls() {
        when(rawRepository.findPipelineInputs(List.of(1L, 2L))).thenReturn(List.of(raw));
        assertThatThrownBy(() -> service.compareRaw(List.of(1L, 2L))).isInstanceOf(CustomException.class);
        raw.dismiss(com.wishconnect.domain.scholarship.entity.RawDismissReason.OTHER, "검수", LocalDateTime.now());
        assertThatThrownBy(() -> service.compareRaw(List.of(1L))).isInstanceOf(CustomException.class);
        verifyNoInteractions(client, runRepository);
    }

    @Test
    void pageCaptureDoesNotCallModelAndRetainsExactSource() {
        var page = Jsoup.parse("<article>신청기간은 2026.11.18일부터 11.26일까지이며 자세한 지원 조건은 첨부파일을 참고하세요.</article>");
        service.capturePage(scholarship, page, "https://example.edu/1");
        var captured = org.mockito.ArgumentCaptor.forClass(ScholarshipPipelineRun.class);
        verify(runRepository).save(captured.capture());
        assertThat(captured.getValue().getStatus()).isEqualTo("CAPTURED");
        assertThat(captured.getValue().getInputJson()).contains("KOSAF_DETAIL", "rawHtml");
        verifyNoInteractions(client);
    }

    @Test
    void disabledByDefaultPathDoesNotReadOrWriteData() {
        ReflectionTestUtils.setField(service, "enabled", false);
        service.capturePage(scholarship, Jsoup.parse("<article>본문</article>"), "https://example.edu/1");
        assertThatThrownBy(() -> service.compareRaw(List.of(1L))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(rawRepository, runRepository, client);
    }
}
