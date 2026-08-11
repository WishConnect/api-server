package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.dto.CalendarScope;
import com.wishconnect.domain.scholarship.dto.ScholarshipCalendarResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipCalendarResponse.EventType;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScholarshipCalendarServiceTest {

	private static final UUID USER_ID = UUID.randomUUID();

	@Mock
	private ScholarshipRepository scholarshipRepository;
	@Mock
	private ScrapRepository scrapRepository;
	@Mock
	private ScholarshipRecommendationService scholarshipRecommendationService;

	@InjectMocks
	private ScholarshipCalendarService service;

	private Scholarship scholarship(Long id, LocalDateTime startAt, LocalDateTime endAt) {
		Scholarship s = Scholarship.builder()
				.title("미래인재 장학금")
				.provider("위시커넥트재단")
				.scholarshipType(ScholarshipType.EXTERNAL)
				.recruitmentStatus(RecruitmentStatus.OPEN)
				.applicationStartAt(startAt)
				.applicationEndAt(endAt)
				.build();
		ReflectionTestUtils.setField(s, "id", id);
		return s;
	}

	@Test
	@DisplayName("모집 시작과 마감을 각각 별개 이벤트로 준다")
	void startAndDeadlineBecomeSeparateEvents() {
		given(scholarshipRepository.findScheduledBetween(any(), any())).willReturn(List.of(
				scholarship(1L, LocalDateTime.of(2026, 7, 10, 10, 0), LocalDateTime.of(2026, 7, 23, 18, 0))));
		given(scholarshipRecommendationService.filterEligibleIds(any(), any())).willReturn(Set.of(1L));

		ScholarshipCalendarResponse response = service.getCalendar(USER_ID, 2026, 7, CalendarScope.MATCHED);

		assertThat(response.events()).hasSize(2);
		assertThat(response.events().get(0).type()).isEqualTo(EventType.START);
		assertThat(response.events().get(0).date()).isEqualTo(LocalDate.of(2026, 7, 10));
		assertThat(response.events().get(1).type()).isEqualTo(EventType.DEADLINE);
		assertThat(response.events().get(1).date()).isEqualTo(LocalDate.of(2026, 7, 23));
	}

	/** 지난달에 시작해 이번 달에 마감하는 공고는 마감만 보여야 한다. */
	@Test
	@DisplayName("조회한 달에 걸치지 않는 날짜는 이벤트로 만들지 않는다")
	void onlyDatesInsideTargetMonthBecomeEvents() {
		given(scholarshipRepository.findScheduledBetween(any(), any())).willReturn(List.of(
				scholarship(1L, LocalDateTime.of(2026, 6, 20, 0, 0), LocalDateTime.of(2026, 7, 5, 0, 0))));
		given(scholarshipRecommendationService.filterEligibleIds(any(), any())).willReturn(Set.of(1L));

		ScholarshipCalendarResponse response = service.getCalendar(USER_ID, 2026, 7, CalendarScope.MATCHED);

		assertThat(response.events()).hasSize(1);
		assertThat(response.events().get(0).type()).isEqualTo(EventType.DEADLINE);
	}

	@Test
	@DisplayName("markedDates 는 이벤트가 있는 날짜를 중복 없이 오름차순으로 준다")
	void markedDatesAreDistinctAndSorted() {
		given(scholarshipRepository.findScheduledBetween(any(), any())).willReturn(List.of(
				scholarship(1L, LocalDateTime.of(2026, 7, 23, 9, 0), LocalDateTime.of(2026, 7, 31, 18, 0)),
				scholarship(2L, null, LocalDateTime.of(2026, 7, 23, 18, 0))));
		given(scholarshipRecommendationService.filterEligibleIds(any(), any())).willReturn(Set.of(1L, 2L));

		ScholarshipCalendarResponse response = service.getCalendar(USER_ID, 2026, 7, CalendarScope.MATCHED);

		assertThat(response.markedDates())
				.containsExactly(LocalDate.of(2026, 7, 23), LocalDate.of(2026, 7, 31));
	}

	@Test
	@DisplayName("MATCHED 는 지원 가능한 공고만 남긴다")
	void matchedScopeKeepsOnlyEligible() {
		given(scholarshipRepository.findScheduledBetween(any(), any())).willReturn(List.of(
				scholarship(1L, null, LocalDateTime.of(2026, 7, 10, 0, 0)),
				scholarship(2L, null, LocalDateTime.of(2026, 7, 20, 0, 0))));
		given(scholarshipRecommendationService.filterEligibleIds(any(), any())).willReturn(Set.of(2L));

		ScholarshipCalendarResponse response = service.getCalendar(USER_ID, 2026, 7, CalendarScope.MATCHED);

		assertThat(response.events()).hasSize(1);
		assertThat(response.events().get(0).scholarshipId()).isEqualTo(2L);
	}

	@Test
	@DisplayName("SCRAPPED 는 스크랩한 공고만 남긴다")
	void scrappedScopeKeepsOnlyScrapped() {
		given(scholarshipRepository.findScheduledBetween(any(), any())).willReturn(List.of(
				scholarship(1L, null, LocalDateTime.of(2026, 7, 10, 0, 0)),
				scholarship(2L, null, LocalDateTime.of(2026, 7, 20, 0, 0))));
		given(scrapRepository.findScrappedScholarshipIds(any(), any())).willReturn(List.of(1L));

		ScholarshipCalendarResponse response = service.getCalendar(USER_ID, 2026, 7, CalendarScope.SCRAPPED);

		assertThat(response.events()).hasSize(1);
		assertThat(response.events().get(0).scholarshipId()).isEqualTo(1L);
		verify(scholarshipRecommendationService, never()).filterEligibleIds(any(), any());
	}

	@Test
	@DisplayName("ALL 은 거르지 않고 추가 조회도 하지 않는다")
	void allScopeSkipsFiltering() {
		given(scholarshipRepository.findScheduledBetween(any(), any())).willReturn(List.of(
				scholarship(1L, null, LocalDateTime.of(2026, 7, 10, 0, 0)),
				scholarship(2L, null, LocalDateTime.of(2026, 7, 20, 0, 0))));

		ScholarshipCalendarResponse response = service.getCalendar(USER_ID, 2026, 7, CalendarScope.ALL);

		assertThat(response.events()).hasSize(2);
		verify(scholarshipRecommendationService, never()).filterEligibleIds(any(), any());
		verify(scrapRepository, never()).findScrappedScholarshipIds(any(), any());
	}

	@Test
	@DisplayName("scope 를 생략하면 MATCHED 로 본다")
	void scopeDefaultsToMatched() {
		given(scholarshipRepository.findScheduledBetween(any(), any())).willReturn(List.of());

		assertThat(service.getCalendar(USER_ID, 2026, 7, null).scope()).isEqualTo(CalendarScope.MATCHED);
	}

	@Test
	@DisplayName("year·month 를 생략하면 이번 달을 본다")
	void monthDefaultsToCurrent() {
		given(scholarshipRepository.findScheduledBetween(any(), any())).willReturn(List.of());

		ScholarshipCalendarResponse response = service.getCalendar(USER_ID, null, null, CalendarScope.ALL);

		YearMonth now = YearMonth.now();
		assertThat(response.year()).isEqualTo(now.getYear());
		assertThat(response.month()).isEqualTo(now.getMonthValue());
	}

	@Test
	@DisplayName("year 만 주는 등 짝이 맞지 않으면 400 이다")
	void partialYearMonthIsRejected() {
		assertThatThrownBy(() -> service.getCalendar(USER_ID, 2026, null, CalendarScope.ALL))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
	}

	@Test
	@DisplayName("month 범위를 벗어나면 400 이다")
	void invalidMonthIsRejected() {
		assertThatThrownBy(() -> service.getCalendar(USER_ID, 2026, 13, CalendarScope.ALL))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
	}

	/** 오타로 터무니없는 연도가 들어와 전체 스캔이 도는 걸 막는다. */
	@Test
	@DisplayName("연도 범위를 벗어나면 400 이다")
	void invalidYearIsRejected() {
		assertThatThrownBy(() -> service.getCalendar(USER_ID, 12026, 7, CalendarScope.ALL))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
	}

	@Test
	@DisplayName("해당 월에 일정이 없으면 빈 목록을 준다")
	void emptyMonthReturnsEmptyLists() {
		given(scholarshipRepository.findScheduledBetween(any(), any())).willReturn(List.of());

		ScholarshipCalendarResponse response = service.getCalendar(USER_ID, 2026, 7, CalendarScope.MATCHED);

		assertThat(response.events()).isEmpty();
		assertThat(response.markedDates()).isEmpty();
	}
}
