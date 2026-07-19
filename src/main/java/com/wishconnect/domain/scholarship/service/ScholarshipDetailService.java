package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipDetailResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipDetailResponse.RequiredDocument;
import com.wishconnect.domain.scholarship.dto.ScholarshipDetailResponse.ScheduleStep;
import com.wishconnect.domain.scholarship.dto.ScholarshipDetailResponse.Summary;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.entity.ScholarshipTimeline;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipTimelineRepository;
import com.wishconnect.domain.common.repository.ImageRepository;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
장학금 상세 조회입니다. 요약 정보 테이블(조건 원문을 유형별로 매핑),
선발 일정 타임라인(없으면 모집기간으로 대체), 제출 서류 목록, 매칭 사유를 포함합니다.
 */
@Service
@RequiredArgsConstructor
public class ScholarshipDetailService {

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd");

	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final ScholarshipDocumentRepository scholarshipDocumentRepository;
	private final ScholarshipTimelineRepository scholarshipTimelineRepository;
	private final ScrapRepository scrapRepository;
	private final ScholarshipRecommendationService scholarshipRecommendationService;
	private final ImageRepository imageRepository;
	private final ImageStorageService imageStorageService;

	@Transactional(readOnly = true)
	public ScholarshipDetailResponse getDetail(UUID userId, Long scholarshipId) {
		Scholarship scholarship = scholarshipRepository.findById(scholarshipId)
				.filter(found -> found.getDeletedAt() == null)
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));

		List<ScholarshipCondition> conditions =
				scholarshipConditionRepository.findAllByScholarshipId(scholarshipId);
		List<RequiredDocument> documents =
				scholarshipDocumentRepository.findAllByScholarshipIdOrderByDisplayOrderAsc(scholarshipId).stream()
						.map(document -> new RequiredDocument(document.getName(), null))
						.toList();
		List<ScheduleStep> schedule = buildSchedule(
				scholarshipTimelineRepository.findAllByScholarshipIdOrderByDisplayOrderAsc(scholarshipId),
				scholarship);

		return new ScholarshipDetailResponse(
				scholarship.getId(),
				scholarship.getTitle(),
				scholarship.getProvider(),
				scholarship.getRecruitmentStatus() == null ? null : scholarship.getRecruitmentStatus().name(),
				scholarship.getApplicationEndAt(),
				CuratedScholarshipResponse.calculateDday(scholarship.getApplicationEndAt()),
				scrapRepository.existsByUserIdAndScholarshipId(userId, scholarshipId),
				List.of(),
				imageRepository.findFirstByEntityTypeAndEntityIdOrderByIdAsc(
								ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarshipId)
						.map(image -> imageStorageService.publicUrl(image.getS3Key()))
						.orElse(null),
				scholarship.getHomepageUrl(),
				buildSummary(scholarship, conditions),
				schedule,
				documents,
				scholarshipRecommendationService.getMatchReasons(userId, scholarship, conditions)
		);
	}

	/** 조건 원문을 유형별로 묶어 요약 테이블 필드에 매핑한다. 데이터가 없는 항목은 null. */
	private Summary buildSummary(Scholarship scholarship, List<ScholarshipCondition> conditions) {
		Map<ConditionType, String> byType = conditions.stream()
				.filter(condition -> condition.getValueString() != null && !condition.getValueString().isBlank())
				.collect(Collectors.groupingBy(ScholarshipCondition::getConditionType,
						Collectors.mapping(ScholarshipCondition::getValueString, Collectors.joining(" / "))));

		String targetAudience = joinNonNull(byType.get(ConditionType.UNIVERSITY_TYPE),
				byType.get(ConditionType.GRADE_LEVEL));
		return new Summary(
				targetAudience,
				CuratedScholarshipResponse.formatAmount(scholarship.getAmount()),
				scholarship.getSelectionCount() == null ? null : scholarship.getSelectionCount() + "명",
				byType.get(ConditionType.MAJOR_FIELD),
				byType.get(ConditionType.FINANCIAL_AID_TYPE),
				byType.get(ConditionType.RESTRICTION),
				scholarship.getProvider(),
				null,
				byType.get(ConditionType.SPECIFIC_QUALIFICATION),
				byType.get(ConditionType.ACADEMIC_CRITERIA),
				byType.get(ConditionType.INCOME_CRITERIA),
				byType.get(ConditionType.RECOMMENDATION_REQUIRED),
				formatPeriod(scholarship.getApplicationStartAt(), scholarship.getApplicationEndAt()),
				null
		);
	}

	/** 타임라인이 있으면 그대로, 없으면 모집기간을 "서류접수" 단일 스텝으로 대체한다. */
	private List<ScheduleStep> buildSchedule(List<ScholarshipTimeline> timelines, Scholarship scholarship) {
		if (!timelines.isEmpty()) {
			return timelines.stream()
					.map(timeline -> new ScheduleStep(
							timeline.getTitle(),
							formatDateRange(timeline.getStartDate(), timeline.getEndDate()),
							scheduleStatus(timeline.getStartDate(), timeline.getEndDate())))
					.toList();
		}
		if (scholarship.getApplicationStartAt() == null && scholarship.getApplicationEndAt() == null) {
			return List.of();
		}
		LocalDate start = scholarship.getApplicationStartAt() == null
				? null : scholarship.getApplicationStartAt().toLocalDate();
		LocalDate end = scholarship.getApplicationEndAt() == null
				? null : scholarship.getApplicationEndAt().toLocalDate();
		return List.of(new ScheduleStep("서류접수", formatDateRange(start, end), scheduleStatus(start, end)));
	}

	private String scheduleStatus(LocalDate startDate, LocalDate endDate) {
		LocalDate today = LocalDate.now();
		if (endDate != null && endDate.isBefore(today)) {
			return "CLOSED";
		}
		if (startDate != null && startDate.isAfter(today)) {
			return "UPCOMING";
		}
		return "CURRENT";
	}

	private String formatDateRange(LocalDate start, LocalDate end) {
		if (start == null && end == null) {
			return null;
		}
		if (start == null) {
			return "~" + DATE_FORMAT.format(end);
		}
		if (end == null) {
			return DATE_FORMAT.format(start) + "~";
		}
		return DATE_FORMAT.format(start) + " ~ " + DATE_FORMAT.format(end);
	}

	private String formatPeriod(LocalDateTime start, LocalDateTime end) {
		return formatDateRange(start == null ? null : start.toLocalDate(), end == null ? null : end.toLocalDate());
	}

	private String joinNonNull(String first, String second) {
		if (first == null) {
			return second;
		}
		return second == null ? first : first + " / " + second;
	}
}
