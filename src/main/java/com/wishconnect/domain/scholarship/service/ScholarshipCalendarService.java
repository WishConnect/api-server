package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.CalendarScope;
import com.wishconnect.domain.scholarship.dto.ScholarshipCalendarResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipCalendarResponse.CalendarEvent;
import com.wishconnect.domain.scholarship.dto.ScholarshipCalendarResponse.EventType;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
홈 화면 "이번 달 일정" 달력.

한 장학금에서 모집 시작·마감을 각각 별개 이벤트로 만든다. 시안이 두 가지를 같은 목록에
섞어 보여주기 때문이다("7/10 모집 시작", "7/23 마감").

조회 대상을 scope 로 좁히는 이유: 전체 장학금을 다 띄우면 달력이 빽빽해 쓸모가 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScholarshipCalendarService {

	/** 조회 가능한 연도 범위. 오타로 터무니없는 값이 들어와 전체 스캔이 도는 걸 막는다. */
	private static final int MIN_YEAR = 2000;
	private static final int MAX_YEAR = 2100;

	private final ScholarshipRepository scholarshipRepository;
	private final ScrapRepository scrapRepository;
	private final ScholarshipRecommendationService scholarshipRecommendationService;

	public ScholarshipCalendarResponse getCalendar(UUID userId, Integer year, Integer month,
			CalendarScope scope) {
		YearMonth target = resolveMonth(year, month);
		CalendarScope resolvedScope = scope == null ? CalendarScope.MATCHED : scope;

		LocalDateTime from = target.atDay(1).atStartOfDay();
		LocalDateTime to = target.plusMonths(1).atDay(1).atStartOfDay();

		List<Scholarship> candidates = scholarshipRepository.findScheduledBetween(from, to);
		List<Scholarship> filtered = applyScope(userId, candidates, resolvedScope);

		List<CalendarEvent> events = toEvents(filtered, target);
		List<LocalDate> markedDates = events.stream()
				.map(CalendarEvent::date)
				.distinct()
				.sorted()
				.toList();

		return new ScholarshipCalendarResponse(
				target.getYear(), target.getMonthValue(), resolvedScope, markedDates, events);
	}

	/** year·month 를 생략하면 이번 달을 본다(홈 진입 기본값). */
	private YearMonth resolveMonth(Integer year, Integer month) {
		if (year == null && month == null) {
			return YearMonth.now();
		}
		if (year == null || month == null) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		if (year < MIN_YEAR || year > MAX_YEAR || month < 1 || month > 12) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		return YearMonth.of(year, month);
	}

	private List<Scholarship> applyScope(UUID userId, List<Scholarship> candidates, CalendarScope scope) {
		if (candidates.isEmpty() || scope == CalendarScope.ALL) {
			return candidates;
		}
		Set<Long> allowedIds = switch (scope) {
			case SCRAPPED -> new HashSet<>(scrapRepository.findScrappedScholarshipIds(
					userId, candidates.stream().map(Scholarship::getId).toList()));
			case MATCHED -> scholarshipRecommendationService.filterEligibleIds(userId, candidates);
			case ALL -> Set.of();
		};
		return candidates.stream()
				.filter(scholarship -> allowedIds.contains(scholarship.getId()))
				.toList();
	}

	/**
	 * 시작·마감을 각각 이벤트로 편다. 그 달에 걸치는 쪽만 넣는다 —
	 * 지난달에 시작해 이번 달에 마감하는 공고는 마감 이벤트만 나와야 한다.
	 */
	private List<CalendarEvent> toEvents(List<Scholarship> scholarships, YearMonth target) {
		List<CalendarEvent> events = new ArrayList<>();
		for (Scholarship scholarship : scholarships) {
			addIfInMonth(events, scholarship, scholarship.getApplicationStartAt(), EventType.START, target);
			addIfInMonth(events, scholarship, scholarship.getApplicationEndAt(), EventType.DEADLINE, target);
		}
		events.sort(Comparator.comparing(CalendarEvent::date)
				.thenComparing(CalendarEvent::type)
				.thenComparing(CalendarEvent::scholarshipId));
		return events;
	}

	private void addIfInMonth(List<CalendarEvent> events, Scholarship scholarship,
			LocalDateTime at, EventType type, YearMonth target) {
		if (at == null || !YearMonth.from(at).equals(target)) {
			return;
		}
		events.add(new CalendarEvent(
				at.toLocalDate(), type, scholarship.getId(),
				scholarship.getTitle(), scholarship.getProvider()));
	}
}
