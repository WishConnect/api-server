package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.dto.ParsedNotice;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class NoticeTimelineParserTest {

    private final UnivNoticeLlmParser parser = new UnivNoticeLlmParser(new ObjectMapper());
    private ParsedNotice notice(String rows) {
        return parser.readResponse("{\"timeline\":" + rows + "}").orElseThrow();
    }

    @Test
    void extractsGroundedStagesIncludingShortenedRangeAndTbd() {
        String body = "서류심사: 2026.11.01 ~ 11.05. 면접: 2026년 11월 20일. 최종 발표: 12월 중 예정.";
        var notice = notice("""
                [{"stageCode":"DOC_REVIEW","dateType":"RANGE","startDate":"2026-11-01","endDate":"2026-11-05",
                    "evidence":"서류심사: 2026.11.01 ~ 11.05."},
                 {"stageCode":"INTERVIEW","dateType":"SINGLE","startDate":"2026-11-20","evidence":"면접: 2026년 11월 20일."},
                 {"stageCode":"FINAL_RESULT","dateType":"TBD","dateText":"12월 중 예정","evidence":"최종 발표: 12월 중 예정."}]
                """);
        var items = NoticeTimelineParser.resolve(notice, body, LocalDate.of(2026, 10, 8));
        assertThat(items).hasSize(3);
        assertThat(items.get(0).endDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(items.get(1).endDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(items.get(2).dateText()).isEqualTo("12월 중 예정");
    }

    @Test
    void rejectsInventedDateYearStageEvidenceAndNullRows() {
        String body = "면접: 2026.11.20. 서류 합격자 발표: 2026.11.10.";
        var notice = notice("""
                [null, {},
                 {"stageCode":"INTERVIEW","dateType":"SINGLE","startDate":"2026-11-21","evidence":"면접: 2026.11.20."},
                 {"stageCode":"INTERVIEW","dateType":"SINGLE","startDate":"2025-11-20","evidence":"면접: 2026.11.20."},
                 {"stageCode":"PAYMENT","dateType":"SINGLE","startDate":"2026-11-20","evidence":"면접: 2026.11.20."},
                 {"stageCode":"FINAL_RESULT","dateType":"SINGLE","startDate":"2026-11-10",
                    "evidence":"서류 합격자 발표: 2026.11.10."},
                 {"stageCode":"INTERVIEW","dateType":"SINGLE","startDate":"2026-11-20",
                    "evidence":"면접일은 2026.11.20 입니다"}]
                """);
        assertThat(NoticeTimelineParser.resolve(notice, body, LocalDate.of(2026, 10, 8))).isEmpty();
    }

    @Test
    void infersMissingYearFromSourceAndRollsDecemberIntoJanuary() {
        String body = "서류심사: 12.28 ~ 1.05.";
        var notice = notice("""
                [{"stageCode":"DOC_REVIEW","dateType":"RANGE","startDate":"2024-12-28","endDate":"2024-01-05",
                    "evidence":"서류심사: 12.28 ~ 1.05."}]
                """);
        var item = NoticeTimelineParser.resolve(notice, body, LocalDate.of(2026, 12, 1)).get(0);
        assertThat(item.startDate()).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(item.endDate()).isEqualTo(LocalDate.of(2027, 1, 5));
    }

    @Test
    void rejectsInvalidRowsWithoutLosingValidNeighborAndRemovesDuplicates() {
        String body = "면접: 2026.11.20. 서류심사: 2026.11.05 ~ 2026.11.01.";
        var notice = notice("""
                [{"stageCode":"DOC_REVIEW","dateType":"RANGE","startDate":"2026-11-05","endDate":"2026-11-01",
                    "evidence":"서류심사: 2026.11.05 ~ 2026.11.01."},
                 {"stageCode":"INTERVIEW","dateType":"SINGLE","startDate":"2026-11-20","evidence":"면접: 2026.11.20."},
                 {"stageCode":"INTERVIEW","dateType":"SINGLE","startDate":"2026-11-20","evidence":"면접: 2026.11.20."}]
                """);
        assertThat(NoticeTimelineParser.resolve(notice, body, LocalDate.of(2026, 10, 8))).hasSize(1);
    }

    @Test
    void doesNotInventTbdTextOrApplicationStage() {
        String body = "면접: 추후 공지 예정. 접수: 2026.11.20.";
        var notice = notice("""
                [{"stageCode":"INTERVIEW","dateType":"TBD","dateText":"12월 중 예정","evidence":"면접: 추후 공지 예정."},
                 {"stageCode":"APPLICATION","dateType":"SINGLE","startDate":"2026-11-20","evidence":"접수: 2026.11.20."}]
                """);
        assertThat(NoticeTimelineParser.resolve(notice, body, LocalDate.of(2026, 10, 8))).isEmpty();
    }

    @Test
    void rejectsDateTakenFromDifferentStageInSameQuotation() {
        String body = "서류 합격 발표: 2026.11.10. 면접: 2026.11.20.";
        var notice = notice("""
                [{"stageCode":"INTERVIEW","dateType":"SINGLE","startDate":"2026-11-10",
                    "evidence":"서류 합격 발표: 2026.11.10. 면접: 2026.11.20."}]
                """);
        assertThat(NoticeTimelineParser.resolve(notice, body, LocalDate.of(2026, 10, 8))).isEmpty();
    }

    @Test
    void acceptsShortExplicitTbdText() {
        var notice = notice("""
                [{"stageCode":"INTERVIEW","dateType":"TBD","dateText":"미정","evidence":"면접 일정은 미정입니다."}]
                """);
        assertThat(NoticeTimelineParser.resolve(notice, "면접 일정은 미정입니다.", LocalDate.of(2026, 10, 8)))
                .hasSize(1);
    }

    @Test
    void keepsOldResponsesCompatibleAndRequiresTimelineInStructuredOutput() {
        assertThat(parser.readResponse("{\"title\":\"기존 공고\"}").orElseThrow().timeline()).isNull();
        assertThat(parser.buildRequest(null, "본문").outputSchema().get("required").toString()).contains("timeline");
    }

	@Test
	void preservesGroundedStageRangesAndPartialDates() {
		String doc = "서류 합격 및 면접 대상자 발표 : 2026년 12월 14일(월) ~ 18일(금)";
		String result = "최종 합격자 발표 : 2026년 12월 23일(수) ~ 25일(금)";
		String select = "장학생 선발 : 2026. 12 초";
		String month = "장학생 발표 : 2026년 12월";
		var notice = notice("""
				[{"stageCode":"DOC_RESULT","dateType":"RANGE","startDate":"2026-12-14",
				  "endDate":"2026-12-18","evidence":"%s"},
				 {"stageCode":"FINAL_RESULT","dateType":"RANGE","startDate":"2026-12-23",
				  "endDate":"2026-12-25","evidence":"%s"},
				 {"stageCode":"CUSTOM","dateType":"TBD","dateText":"2026. 12 초","evidence":"%s"},
				 {"stageCode":"FINAL_RESULT","dateType":"TBD","dateText":"2026년 12월","evidence":"%s"}]
				""".formatted(doc, result, select, month));
		var items = NoticeTimelineParser.resolve(notice, String.join("\n", doc, result, select, month),
				LocalDate.of(2026, 11, 16));

		assertThat(items).hasSize(4);
		assertThat(items.get(0).endDate()).isEqualTo(LocalDate.of(2026, 12, 18));
		assertThat(items.get(1).endDate()).isEqualTo(LocalDate.of(2026, 12, 25));
		assertThat(items.get(2).title()).isEqualTo("장학생 선발");
		assertThat(items.get(3).dateText()).isEqualTo("2026년 12월");
	}

	@Test
	void rejectsInventedRangeForSeparateRegionalInterviews() {
		String quote = "2차 면접심사: 2026. 12. 29.(화) 울산 / 12. 30.(수) 서울";
		var notice = notice("""
				[{"stageCode":"INTERVIEW","dateType":"RANGE","startDate":"2026-12-29",
				  "endDate":"2026-12-30","evidence":"%s"}]
				""".formatted(quote));

		assertThat(NoticeTimelineParser.resolve(notice, quote, LocalDate.of(2026, 11, 16))).isEmpty();
	}

	@Test
	void recoversSourceRowsMissedByModelOnThreeNoticeFormats() {
		var body = """
				서류 합격 및 면접 대상자 발표: 2026년 12월 14일(월) ~ 18일(금)
				2차면접심사: 2026. 12. 29.(화) 울산 / 12. 30.(수) 서울
				나. 장학생 선발: 2026. 12 초
				최종 합격자 발표: 2026년 12월 23일(수) ~ 25일(금)
				면접 확인서 제출기한: 2026년 12월 10일
				""";
		var items = NoticeTimelineParser.withSourceEvidence(notice("[]"), body, LocalDate.of(2026, 11, 16));
		assertThat(items).hasSize(5);
		assertThat(items.get(0).endDate()).isEqualTo(LocalDate.of(2026, 12, 18));
		assertThat(items.subList(1, 3)).extracting(item -> item.note()).containsExactly("울산", "서울");
		assertThat(items.get(3).dateText()).isEqualTo("2026. 12 초");
		assertThat(items.get(4).endDate()).isEqualTo(LocalDate.of(2026, 12, 25));
	}

}
