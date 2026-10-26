package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.scholarship.dto.ParsedNotice;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipTimeline;
import com.wishconnect.domain.scholarship.entity.TimelineOrigin;
import com.wishconnect.domain.scholarship.repository.ScholarshipTimelineRepository;
import com.wishconnect.domain.scholarship.util.UnivNoticeLlmParser;
import java.time.LocalDate;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class CrawledScholarshipTimelineServiceTest {

    private final ScholarshipTimelineRepository repository = mock(ScholarshipTimelineRepository.class);
    private final LlmClient client = mock(LlmClient.class);
    private final UnivNoticeLlmParser parser = new UnivNoticeLlmParser(new ObjectMapper());
    private final CrawledScholarshipTimelineService service =
            new CrawledScholarshipTimelineService(repository, parser, client);
    private Scholarship scholarship;
    private static final String BODY = "서류 합격 발표: 2026.11.10. 면접: 2026.11.20. 최종 합격 발표: 2026.11.30. 장학금 지급: 12월 중 예정.";
    private static final String RESPONSE = """
            {"timeline":[{"stageCode":"INTERVIEW","dateType":"SINGLE","startDate":"2026-11-20",
            "evidence":"면접: 2026.11.20."}]}
            """;

    @BeforeEach
    void setUp() {
        scholarship = Scholarship.builder().title("장학금").provider("재단").build();
        ReflectionTestUtils.setField(scholarship, "id", 1L);
        when(repository.findAllByScholarshipIdOrderByDisplayOrderAsc(1L)).thenReturn(List.of());
    }

    @Test
    void crawlsScheduleEvenWhenApiPeriodIsEmptyAndReturnsItInDetailAssembly() {
        when(client.chat(any())).thenReturn(RESPONSE);
        assertThat(service.enrichFromPage(scholarship, Jsoup.parse("<article>" + BODY + "</article>"))).isEqualTo(1);
        ArgumentCaptor<ScholarshipTimeline> captured = ArgumentCaptor.forClass(ScholarshipTimeline.class);
        verify(repository).save(captured.capture());
        var row = captured.getValue();
        assertThat(row.getOrigin()).isEqualTo(TimelineOrigin.LLM);
        assertThat(row.getEvidence()).isEqualTo("면접: 2026.11.20.");
        var steps = SelectionScheduleAssembler.assemble(List.of(row), null, null, LocalDate.of(2026, 10, 8));
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).date()).isEqualTo("2026.11.20");
    }

    @Test
    void doesNotOverwriteManualScheduleOrCallLlm() {
        when(repository.findAllByScholarshipIdOrderByDisplayOrderAsc(1L)).thenReturn(List.of(
                ScholarshipTimeline.builder().origin(TimelineOrigin.MANUAL).build()));
        assertThat(service.enrichFromPage(scholarship, Jsoup.parse("<article>" + BODY + "</article>"))).isZero();
        verifyNoInteractions(client);
        verify(repository, never()).deleteByScholarship(any());
    }

    @Test
    void preservesIntentionallyClearedTimeline() {
        scholarship.lockTimeline();
        assertThat(service.replaceFromParsed(scholarship, parser.readResponse(RESPONSE).orElseThrow(), BODY,
                LocalDate.of(2026, 10, 8))).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    void doesNotEraseExistingTimelineForEmptyOrInvalidResponse() {
        ParsedNotice notice = parser.readResponse("{\"timeline\":[]}").orElseThrow();
        assertThat(service.replaceFromParsed(scholarship, notice, BODY, LocalDate.of(2026, 10, 8))).isZero();
        verify(repository, never()).deleteByScholarship(any());
    }

    @Test
    void leavesImageOnlySourceForOcrAndSurvivesLlmFailure() {
        assertThat(service.enrichFromPage(scholarship,
                Jsoup.parse("<article><img src='/poster.png'></article>"))).isZero();
        verifyNoInteractions(client);
        when(client.chat(any())).thenThrow(new IllegalStateException("LLM unavailable"));
        assertThat(service.enrichFromPage(scholarship, Jsoup.parse("<article>" + BODY + "</article>"))).isZero();
        verify(repository, never()).deleteByScholarship(any());
    }
}
