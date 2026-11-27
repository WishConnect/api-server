package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.wishconnect.domain.common.repository.ImageRepository;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.insight.client.NaverSearchClient;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import java.util.List;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ScholarshipEnrichmentServiceTest {

	private final ScholarshipRepository repository = mock(ScholarshipRepository.class);
	private final RawScholarshipRepository rawRepository = mock(RawScholarshipRepository.class);
	private final CrawledScholarshipTimelineService timelines = mock(CrawledScholarshipTimelineService.class);
	private final ScholarshipEnrichmentService service = new ScholarshipEnrichmentService(repository, rawRepository,
			mock(ScholarshipDocumentRepository.class), mock(ImageRepository.class), mock(ImageStorageService.class),
			mock(NaverSearchClient.class), timelines, mock(ScholarshipPipelineReviewService.class));
	private static final String URL = "https://example.org/notice";
	private static final String HTML = "<article>최종 합격 발표: 2026.12.15. 장학생 선발 일정이며 자세한 내용은 공고를 참고하세요.</article>";

	@Test
	void unchangedSuccessfullyParsedPageDoesNotCallParserAgain() throws Exception {
		run(HTML, false, 0, false);
		verifyNoInteractions(timelines);
	}

	@Test
	void changedPageUpdatesSnapshotAfterSuccessfulScheduleParsing() throws Exception {
		var raw = run(HTML.replace("12.15", "12.16"), false, 1, true);
		assertThat(raw.getRawHtml()).contains("12.16");
		assertThat(raw.getRawJson()).containsEntry("상품명", "테스트 장학금");
	}

	@Test
	void changedDeadlineForcesParsingEvenWhenPageIsUnchanged() throws Exception {
		run(HTML, true, 0, true);
		verify(timelines).enrichPage(any(), any());
	}

	@Test
	void failedOrEmptyParsingDoesNotCacheChangedPageAsSuccessful() throws Exception {
		var raw = run(HTML.replace("12.15", "12.16"), false, -1, true);
		assertThat(raw.getRawHtml()).isEqualTo(HTML);
	}

	@Test
	void successfulReparseWithoutNewRowsStillRemembersPage() throws Exception {
		var raw = run(HTML.replace("12.15", "12.16"), false, 0, true);
		assertThat(raw.getRawHtml()).contains("12.16");
	}

	private RawScholarship run(String updated, boolean requested, int saved, boolean shouldParse) throws Exception {
		var scholarship = Scholarship.builder().title("테스트 장학금").build();
		ReflectionTestUtils.setField(scholarship, "id", 1L);
		scholarship.applyEnrichment(URL);
		if (requested) scholarship.requestEnrichment();
		var raw = RawScholarship.builder().source("KOSAF_SCHOLARSHIP").sourceId("1")
				.rawJson(java.util.Map.of("상품명", "테스트 장학금")).rawHtml(HTML).build();
		when(repository.findEnrichmentTargets(anyString(), anyBoolean(), any(), any(), any(), any()))
				.thenReturn(List.of(scholarship));
		when(rawRepository.findAllByScholarshipIdOrderByIdDesc(1L)).thenReturn(List.of(raw));
		ReflectionTestUtils.setField(service, "collectSchedule", true);
		var page = Jsoup.parse(updated, URL);
		if (shouldParse) when(timelines.enrichPage(scholarship, page)).thenReturn(new CrawledScholarshipTimelineService.EnrichmentOutcome(Math.max(0, saved), saved >= 0));
		var connection = mock(Connection.class, RETURNS_SELF);
		when(connection.get()).thenReturn(page);
		try (var jsoup = mockStatic(Jsoup.class, CALLS_REAL_METHODS)) {
			jsoup.when(() -> Jsoup.connect(URL)).thenReturn(connection);
			service.enrich(50);
		}
		return raw;
	}
}
