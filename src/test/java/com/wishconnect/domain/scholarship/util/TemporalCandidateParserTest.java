package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.dto.ParsedNotice;
import com.wishconnect.domain.scholarship.dto.PipelineNoticeInput;
import com.wishconnect.domain.scholarship.dto.TemporalParseResult.Precision;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemporalCandidateParserTest {
    private final TemporalCandidateParser candidate = new TemporalCandidateParser();
    private final ObjectMapper mapper = new ObjectMapper();
    private final UnivNoticeLlmParser legacy = new UnivNoticeLlmParser(mapper);
    private static final LocalDate REFERENCE = LocalDate.of(2026, 11, 2);

    private ParsedNotice notice(Map<String, Object> value) throws Exception {
        return legacy.readResponse(mapper.writeValueAsString(value)).orElseThrow();
    }

    private PipelineNoticeInput input(String title, String body) {
        return new PipelineNoticeInput(1L, 1L, "UNIV_TEST", "https://example.edu/1", title,
                body, List.of(), REFERENCE, "<article>" + body + "</article>", null, false);
    }

    @Test
    void preservesDateOnlyApiWithoutInventingMidnight() throws Exception {
        var input = PipelineNoticeInput.api(1L, 2L, "KOSAF_SCHOLARSHIP", "api", Map.of(
                "상품명", "장학생 모집", "모집시작일", "20261102", "모집종료일", "2026-11-26"), REFERENCE);
        var result = candidate.resolve(input, notice(Map.of()));
        assertThat(result.period().end().date()).isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(result.period().end().time()).isNull();
        assertThat(result.period().end().precision()).isEqualTo(Precision.DATE_ONLY);
    }

    @Test
    void preservesExplicitAfternoonDeadline() throws Exception {
        String quote = "지원서 제출 기한: 2026.11.18. 오전 10시부터 2026.11.26. 오후 4시까지";
        var n = notice(Map.of("applicationStart", "2026-11-18", "applicationEnd", "2026-11-26",
                "periodEvidence", quote));
        var result = candidate.resolve(input("장학생 모집", quote), n);
        assertThat(result.period().start().time()).isEqualTo(LocalTime.of(10, 0));
        assertThat(result.period().end().time()).isEqualTo(LocalTime.of(16, 0));
        assertThat(result.period().end().precision()).isEqualTo(Precision.DATE_TIME);
    }

    @Test
    void acceptsTitleEvidenceMissingFromBody() throws Exception {
        String title = "2026학년도 인석장학회 장학생 선발 안내(11.18. 10:00 ~ 11.26. 16:00)";
        var n = notice(Map.of("applicationStart", "2026-11-18", "applicationEnd", "2026-11-26",
                "periodEvidence", title));
        assertThat(legacy.resolvePeriod(n, "상세 내용은 첨부파일 참고", REFERENCE)).isEmpty();
        var result = candidate.resolve(input(title, "상세 내용은 첨부파일 참고"), n);
        assertThat(result.period().end().time()).isEqualTo(LocalTime.of(16, 0));
    }

    @Test
    void recoversUnambiguousTitleRangeWhenModelOmittedPeriod() throws Exception {
        var result = candidate.resolve(input("장학생 모집(2026.11.18 ~ 11.26)", "본문"), notice(Map.of()));
        assertThat(result.period().end().date()).isEqualTo(LocalDate.of(2026, 11, 26));
    }

    @Test
    void rejectsExplicitWrongYear() throws Exception {
        String quote = "신청기간: 2026.11.01 ~ 2026.11.26.";
        var result = candidate.resolve(input("모집", quote), notice(Map.of(
                "applicationEnd", "2025-11-26", "periodEvidence", quote)));
        assertThat(result.period()).isNull();
        assertThat(result.issues()).contains("PERIOD_DATE_MISMATCH");
    }

    @Test
    void acceptsCompoundDocumentResultLabel() throws Exception {
        String quote = "서류심사 결과 발표: 2026.11.10.";
        var result = stage("DOC_RESULT", "2026-11-10", quote);
        assertThat(result.timeline()).hasSize(1);
    }

    @Test
    void rejectsConfirmationDeadlineAsInterviewDate() throws Exception {
        var result = stage("INTERVIEW", "2026-11-10",
                "면접: 2026.11.20. 면접 확인서 제출기한: 2026.11.10.");
        assertThat(result.timeline()).isEmpty();
        assertThat(result.issues()).contains("TIMELINE_STAGE_AMBIGUOUS");
    }

    @Test
    void rejectsTwoDatesInSingleStageQuote() throws Exception {
        var result = stage("INTERVIEW", "2026-11-10", "면접: 2026.11.10 또는 2026.11.20.");
        assertThat(result.timeline()).isEmpty();
        assertThat(result.issues()).contains("TIMELINE_DATE_RELATION_AMBIGUOUS");
    }

    @Test
    void doesNotUseResultDateOrWorkPeriodAsApplicationDeadline() throws Exception {
        for (String quote : List.of("서류 합격자 발표: 2026.11.10.", "근무기간: 2026.11.01 ~ 2026.11.10.")) {
            assertThat(candidate.resolve(input("안내", quote), notice(Map.of(
                    "applicationEnd", "2026-11-10", "periodEvidence", quote))).period()).isNull();
        }
    }

    @Test
    void doesNotRecoverPaymentTitleAsApplicationPeriod() throws Exception {
        assertThat(candidate.resolve(input("장학금 지급 안내(2026.11.01 ~ 11.10)", ""),
                notice(Map.of())).period()).isNull();
    }

    @Test
    void preservesRealMidnightAndRejectsUnparsedIntradayRange() throws Exception {
        String midnight = "신청기한: 2026.11.26 00:00";
        var n = notice(Map.of("applicationEnd", "2026-11-26", "periodEvidence", midnight));
        assertThat(candidate.resolve(input("모집", midnight), n).period().end().time())
                .isEqualTo(LocalTime.MIDNIGHT);
        String range = "신청기간: 2026.11.26 10:00 ~ 16:00";
        assertThat(candidate.resolve(input("모집", range), notice(Map.of(
                "applicationEnd", "2026-11-26", "periodEvidence", range))).period()).isNull();
    }

    @Test
    void rejectsImpossibleDateAndReportsTruncatedInput() throws Exception {
        String quote = "신청기한: 2026.11.31";
        var source = input("모집", quote);
        source = new PipelineNoticeInput(1L, 1L, source.source(), source.sourceUrl(), source.title(),
                source.body(), List.of(), REFERENCE, source.rawHtml(), null, true);
        var result = candidate.resolve(source, notice(Map.of("applicationEnd", "2026-11-30",
                "periodEvidence", quote)));
        assertThat(result.period()).isNull();
        assertThat(result.issues()).contains("SOURCE_DATE_INVALID", "INPUT_TRUNCATED");
    }

    private com.wishconnect.domain.scholarship.dto.TemporalParseResult stage(
            String stage, String date, String quote) throws Exception {
        return candidate.resolve(input("공지", quote), notice(Map.of("timeline", List.of(Map.of(
                "stageCode", stage, "dateType", "SINGLE", "startDate", date, "evidence", quote)))));
    }
}
