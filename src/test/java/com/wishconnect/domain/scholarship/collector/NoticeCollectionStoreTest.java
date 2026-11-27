package com.wishconnect.domain.scholarship.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NoticeCollectionStoreTest {

	private final RawScholarshipRepository repository = mock(RawScholarshipRepository.class);
	private final NoticeCollectionStore store = new NoticeCollectionStore(repository);
	private static final String ORIGINAL = "<h1 class='artclViewTitle'>장학생 선발</h1><article>"
			+ "지원자격은 해당 학교 재학생이며 장학금 지급을 위한 제출서류를 확인하세요. 최종 발표: 12월 중 예정</article>";

	@Test
	void changedNoticeKeepsIdentityAndScholarshipAndBecomesPending() {
		var original = existing();
		var linked = original.getScholarship();
		when(repository.findBySourceAndSourceId("UNIV_TEST", "1")).thenReturn(Optional.of(original));
		store.save(raw(ORIGINAL.replace("12월 중 예정", "2026.12.15.")));
		assertThat(original.getScholarship()).isSameAs(linked);
		assertThat(original.getParseStatus()).isEqualTo(ParseStatus.PENDING);
		assertThat(original.getRawHtml()).contains("2026.12.15.");
		verify(repository).save(original);
	}

	@Test
	void unchangedNoticeDoesNotRequestAnotherLlmParse() {
		var original = existing();
		when(repository.findBySourceAndSourceId("UNIV_TEST", "1")).thenReturn(Optional.of(original));
		store.save(raw(ORIGINAL + "<script>viewCount=123</script>"));
		assertThat(original.getParseStatus()).isEqualTo(ParseStatus.PARSED);
	}

	@Test
	void titleAndAttachmentChangesAlsoRequestParsing() {
		for (String updated : List.of(ORIGINAL.replace("장학생 선발", "장학생 선발 일정 변경"),
				ORIGINAL + "<a href='/new.pdf'>수정된 일정</a>")) {
			var original = existing();
			when(repository.findBySourceAndSourceId("UNIV_TEST", "1")).thenReturn(Optional.of(original));
			store.save(raw(updated));
			assertThat(original.getParseStatus()).isEqualTo(ParseStatus.PENDING);
		}
	}

	@Test
	void emptyPageCannotEraseOriginalOrItsLink() {
		var original = existing();
		when(repository.findBySourceAndSourceId("UNIV_TEST", "1")).thenReturn(Optional.of(original));
		store.save(raw("<html><body></body></html>"));
		store.save(raw(null));
		assertThat(original.getRawHtml()).isEqualTo(ORIGINAL);
		assertThat(original.getParseStatus()).isEqualTo(ParseStatus.PARSED);
		verify(repository, never()).save(any());
	}

	@Test
	void refreshFetchesExistingNoticeOutsideListAndIsolatesFailure() throws Exception {
		var first = existing();
		var second = existing();
		when(repository.findNoticeRefreshTargets(eq("UNIV_TEST"), any(), any(), any()))
				.thenReturn(List.of(first, second));
		var fetcher = mock(NoticeCollectionStore.Refresher.class);
		when(fetcher.refresh(first)).thenThrow(new IllegalStateException("site unavailable"));
		when(fetcher.refresh(second)).thenReturn(true);
		assertThat(store.refreshDue("UNIV_TEST", fetcher)).isEqualTo(1);
		verify(fetcher).refresh(second);
	}

	private RawScholarship raw(String html) {
		return RawScholarship.builder().source("UNIV_TEST").sourceId("1")
				.sourceUrl("https://example.edu/notice/1").rawHtml(html).build();
	}

	private RawScholarship existing() {
		var raw = raw(ORIGINAL);
		raw.markParsed(Scholarship.builder().title("장학생 선발").build());
		return raw;
	}
}
