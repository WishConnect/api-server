package com.wishconnect.domain.scholarship.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipDetailResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualFullRequest;
import com.wishconnect.domain.scholarship.entity.ConditionNecessity;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.ConditionRef;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.entity.ScholarshipDocument;
import com.wishconnect.domain.scholarship.entity.ScholarshipTimeline;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.entity.TimelineDateType;
import com.wishconnect.domain.scholarship.entity.TimelineOrigin;
import com.wishconnect.domain.scholarship.entity.TimelineStageCode;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipTimelineRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 수기 통합 등록의 DB 작업(장학금·원문·조건·서류·선발 일정)을 한 트랜잭션으로 묶는다. 외부 이미지 다운로드는 포함하지 않는다. */
@Service
@RequiredArgsConstructor
public class ScholarshipManualAggregateStore {

	private final ScholarshipRepository scholarshipRepository;
	private final RawScholarshipRepository rawScholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final ScholarshipDocumentRepository scholarshipDocumentRepository;
	private final ScholarshipTimelineRepository scholarshipTimelineRepository;
	private final ConditionRefResolver conditionRefResolver;
	private final ObjectMapper objectMapper;

	@Transactional
	public SavedAggregate create(ScholarshipManualFullRequest request) {
		validatePeriod(request);
		List<ScholarshipTimelineValidator.Item> timeline = ScholarshipTimelineValidator.normalize(request.timeline());
		String manualKey = Scholarship.MANUAL_SOURCE + ":" + UUID.randomUUID();
		Scholarship scholarship = createScholarship(request, manualKey);

		ScholarshipManualFullRequest.Source source = request.source();
		RawScholarship raw = rawScholarshipRepository.save(RawScholarship.builder()
				.scholarship(scholarship)
				.source(Scholarship.MANUAL_SOURCE)
				.sourceId(manualKey)
				.sourceUrl(source == null ? request.detailUrl() : source.sourceUrl())
				.rawHtml(source == null ? null : source.rawHtml())
				.rawJson(objectMapper.convertValue(request, new TypeReference<>() { }))
				.parseStatus(ParseStatus.PARSED)
				.build());

		int refCount = saveConditions(scholarship, safe(request.conditions()));
		int documentCount = saveDocuments(scholarship, safe(request.documents()));
		saveTimeline(scholarship, safe(timeline));
        if (timeline != null) scholarship.lockTimeline();
		return saved(request, scholarship, raw.getId(), refCount, documentCount);
	}

	/** 실패 원본을 사람이 구조화해 기존 raw 행과 새 scholarship을 연결한다. */
	@Transactional
	public SavedAggregate createFromRaw(Long rawId, ScholarshipManualFullRequest request) {
		validatePeriod(request);
		List<ScholarshipTimelineValidator.Item> timeline = ScholarshipTimelineValidator.normalize(request.timeline());
		RawScholarship raw = rawScholarshipRepository.findById(rawId)
				.orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT));
		if (raw.getScholarship() != null) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		Scholarship scholarship = createScholarship(request, "MANUAL_RAW:" + rawId);
		raw.markParsed(scholarship);
		int refCount = saveConditions(scholarship, safe(request.conditions()));
		int documentCount = saveDocuments(scholarship, safe(request.documents()));
		saveTimeline(scholarship, safe(timeline));
        if (timeline != null) scholarship.lockTimeline();
		return saved(request, scholarship, raw.getId(), refCount, documentCount);
	}

	/**
	 * 통합 편집은 조건·서류 목록을 화면에 보이는 최종 상태로 교체한다.
	 *
	 * <p>선발 일정은 다르다. {@code timeline} 이 null(생략)이면 <b>기존 일정을 그대로 둔다</b> — 일정을 모르는
	 * 옛 화면·다른 경로가 저장하면서 일정을 지우지 않게 하려는 것이다. 빈 목록일 때만 모두 지운다.
	 */
	@Transactional
	public SavedAggregate update(Long scholarshipId, ScholarshipManualFullRequest request) {
		validatePeriod(request);
		List<ScholarshipTimelineValidator.Item> timeline = ScholarshipTimelineValidator.normalize(request.timeline());
		Scholarship scholarship = scholarshipRepository.findById(scholarshipId)
				.filter(value -> !value.isDeleted())
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
		boolean periodChanged = changed(scholarship.getApplicationStartAt(), request.applicationStartAt())
				|| changed(scholarship.getApplicationEndAt(), request.applicationEndAt());
		scholarship.replaceByAdmin(
				request.title().trim(), request.provider(), request.summary(), request.description(),
				request.scholarshipType(), request.applicationStartAt(), request.applicationEndAt(),
				request.recruitmentStatus(), request.selectionCount(), request.amount(), request.homepageUrl(),
				request.detailUrl(), request.noticeKind(), request.combined(), request.submissionMethod(),
				request.submissionChannel(), request.submissionEvidence(), request.contact(),
				request.essayRequirement(), request.essayEvidence(), request.interviewRequirement(),
				request.interviewEvidence());
		// 모집기간 수기 고정: 명시한 값이 우선(false = 자동 수집 값으로 되돌리기). 없으면 기간이 바뀔 때만 켠다.
		if (request.periodLocked() != null) {
			scholarship.changePeriodLocked(request.periodLocked());
		} else if (periodChanged) {
			scholarship.changePeriodLocked(true);
		}

		scholarshipConditionRepository.deleteByScholarship(scholarship);
		scholarshipDocumentRepository.deleteByScholarship(scholarship);
		scholarshipConditionRepository.flush();
		scholarshipDocumentRepository.flush();
		int refCount = saveConditions(scholarship, safe(request.conditions()));
		int documentCount = saveDocuments(scholarship, safe(request.documents()));
		if (timeline != null) {
            scholarship.lockTimeline();
			scholarshipTimelineRepository.deleteByScholarship(scholarship);
			scholarshipTimelineRepository.flush();
			saveTimeline(scholarship, timeline);
		}
		return saved(request, scholarship, null, refCount, documentCount);
	}

	/**
	 * 감사 로그 복구: 조건 목록을 기록 시점 값으로 바꾼다. 참조는 화면 라벨이 아니라 기록된 ID·코드 그대로 되살리고,
	 * 자동 추출 여부도 기록 값을 쓴다(라벨 재해석으로 값이 달라지는 것을 막는다).
	 */
	@Transactional
	public int replaceConditionsFromSnapshot(Long scholarshipId,
			List<AdminScholarshipDetailResponse.ConditionData> conditions) {
		Scholarship scholarship = scholarshipRepository.findById(scholarshipId)
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
		scholarshipConditionRepository.deleteByScholarship(scholarship);
		scholarshipConditionRepository.flush();
		for (AdminScholarshipDetailResponse.ConditionData data : safe(conditions)) {
			ScholarshipCondition condition = ScholarshipCondition.builder()
					.scholarship(scholarship)
					.conditionType(ConditionType.valueOf(data.conditionType()))
					.operator(data.operator() == null ? null : ConditionOperator.valueOf(data.operator()))
					.necessity(data.necessity() == null ? null : ConditionNecessity.valueOf(data.necessity()))
					.valueInt(data.valueInt())
					.valueIntMax(data.valueIntMax())
					.valueString(data.valueString())
					.autoExtracted(data.autoExtracted())
					.build();
			Set<ConditionRef> refs = new java.util.LinkedHashSet<>();
			for (AdminScholarshipDetailResponse.RefData ref : safe(data.refs())) {
				if (ref.refId() != null) {
					refs.add(ConditionRef.ofId(ref.refId()));
				} else if (ref.refCode() != null) {
					refs.add(ConditionRef.ofCode(ref.refCode()));
				}
			}
			condition.applyRefs(refs);
			scholarshipConditionRepository.save(condition);
		}
		return safe(conditions).size();
	}

	/** 감사 로그 복구: 제출 서류 목록을 기록 시점 값으로 바꾼다. */
	@Transactional
	public int replaceDocumentsFromSnapshot(Long scholarshipId,
			List<AdminScholarshipDetailResponse.DocumentData> documents) {
		Scholarship scholarship = scholarshipRepository.findById(scholarshipId)
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
		scholarshipDocumentRepository.deleteByScholarship(scholarship);
		scholarshipDocumentRepository.flush();
		for (AdminScholarshipDetailResponse.DocumentData data : safe(documents)) {
			scholarshipDocumentRepository.save(ScholarshipDocument.builder()
					.scholarship(scholarship)
					.name(data.name())
					.essay(data.essay())
					.displayOrder(data.displayOrder())
					.downloadUrl(data.downloadUrl())
					.build());
		}
		return safe(documents).size();
	}

	/**
	 * 감사 로그 복구: 선발 일정을 기록 시점 값으로 바꾼다. 출처(origin)도 기록 값을 쓰고, 순서는 기록 순서대로 0부터 매긴다.
	 * 호출부가 "기록에 일정 키가 있을 때만" 부른다 — 옛 기록으로 일정을 비우지 않기 위해서다.
	 */
	@Transactional
	public int replaceTimelineFromSnapshot(Long scholarshipId,
			List<AdminScholarshipDetailResponse.TimelineData> timeline) {
		Scholarship scholarship = scholarshipRepository.findById(scholarshipId)
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
		scholarshipTimelineRepository.deleteByScholarship(scholarship);
		scholarshipTimelineRepository.flush();
		List<AdminScholarshipDetailResponse.TimelineData> rows = safe(timeline);
        scholarship.lockTimeline();
		for (int i = 0; i < rows.size(); i++) {
			AdminScholarshipDetailResponse.TimelineData data = rows.get(i);
			scholarshipTimelineRepository.save(ScholarshipTimeline.builder()
					.scholarship(scholarship)
					.stageCode(TimelineStageCode.valueOf(data.stageCode()))
					.title(data.title())
					.dateType(TimelineDateType.valueOf(data.dateType()))
					.startDate(data.startDate())
					.endDate(data.endDate())
					.dateText(data.dateText())
					.note(data.note())
					.evidence(data.evidence())
					.origin(data.origin() == null ? TimelineOrigin.MANUAL : TimelineOrigin.valueOf(data.origin()))
					.displayOrder(i)
					.build());
		}
		return rows.size();
	}

	private Scholarship createScholarship(ScholarshipManualFullRequest request, String dedupKey) {
		Scholarship scholarship = Scholarship.createManual(
				request.title().trim(),
				request.provider(),
				request.summary(),
				request.description(),
				request.scholarshipType() == null ? ScholarshipType.EXTERNAL : request.scholarshipType(),
				request.applicationStartAt(),
				request.applicationEndAt(),
				request.selectionCount(),
				request.amount(),
				request.homepageUrl(),
				dedupKey);
		scholarship.applyManualDetails(
				request.detailUrl(), request.recruitmentStatus(), request.noticeKind(), request.combined(),
				request.submissionMethod(), request.submissionChannel(), request.submissionEvidence(),
				request.contact(), request.essayRequirement(), request.essayEvidence(),
				request.interviewRequirement(), request.interviewEvidence());
		scholarshipRepository.save(scholarship);
		return scholarship;
	}

	private SavedAggregate saved(ScholarshipManualFullRequest request, Scholarship scholarship,
			Long rawId, int refCount, int documentCount) {
		return new SavedAggregate(scholarship.getId(), rawId, request.conditions() == null
				? 0 : request.conditions().size(), refCount, documentCount, scholarship.getTitle());
	}

	private int saveConditions(Scholarship scholarship,
			List<ScholarshipManualFullRequest.Condition> requests) {
		int refCount = 0;
		for (ScholarshipManualFullRequest.Condition request : requests) {
			ScholarshipCondition condition = ScholarshipCondition.builder()
					.scholarship(scholarship)
					.conditionType(request.conditionType())
					.operator(request.operator())
					.necessity(request.necessity())
					.valueInt(request.valueInt())
					.valueIntMax(request.valueIntMax())
					.valueString(request.valueString().trim())
					// 사람이 검수해 넣은 구조화 값이므로 LLM 재추출 대상에서 제외한다.
					.autoExtracted(true)
					.build();
			Set<ConditionRef> refs = conditionRefResolver.resolve(
					request.conditionType(), safe(request.refLabels()));
			refs = new java.util.LinkedHashSet<>(refs);
			for (Long refId : safe(request.refIds())) {
				if (refId != null) refs.add(ConditionRef.ofId(refId));
			}
			for (String refCode : safe(request.refCodes())) {
				if (refCode != null && !refCode.isBlank()) refs.add(ConditionRef.ofCode(refCode.trim()));
			}
			condition.applyRefs(refs);
			refCount += refs.size();
			scholarshipConditionRepository.save(condition);
		}
		return refCount;
	}

	private int saveDocuments(Scholarship scholarship,
			List<ScholarshipManualFullRequest.Document> requests) {
		for (int i = 0; i < requests.size(); i++) {
			ScholarshipManualFullRequest.Document request = requests.get(i);
			scholarshipDocumentRepository.save(ScholarshipDocument.builder()
					.scholarship(scholarship)
					.name(request.name().trim())
					.essay(request.essay())
					.displayOrder(request.displayOrder() == null ? i : request.displayOrder())
					.downloadUrl(request.downloadUrl())
					.build());
		}
		return requests.size();
	}

	/** 순서는 배열 순서(0부터)로 매기고, 사람이 넣은 값이므로 출처는 MANUAL 이다. */
	private void saveTimeline(Scholarship scholarship, List<ScholarshipTimelineValidator.Item> items) {
		for (int i = 0; i < items.size(); i++) {
			ScholarshipTimelineValidator.Item item = items.get(i);
			scholarshipTimelineRepository.save(ScholarshipTimeline.builder()
					.scholarship(scholarship)
					.stageCode(item.stageCode())
					.title(item.title())
					.dateType(item.dateType())
					.startDate(item.startDate())
					.endDate(item.endDate())
					.dateText(item.dateText())
					.note(item.note())
					.evidence(item.evidence())
					.origin(TimelineOrigin.MANUAL)
					.displayOrder(i)
					.build());
		}
	}

	/**
	 * 화면(datetime-local)은 분 단위로 보낸다. 초 이하가 남은 수집 값(23:59:59 등)을 손대지 않고 저장해도
	 * "바뀐 것"으로 보지 않도록 분 단위로 비교한다.
	 */
	private static boolean changed(LocalDateTime current, LocalDateTime requested) {
		if (current == null || requested == null) {
			return current != requested;
		}
		return !current.truncatedTo(ChronoUnit.MINUTES).equals(requested.truncatedTo(ChronoUnit.MINUTES));
	}

	private void validatePeriod(ScholarshipManualFullRequest request) {
		if (request.applicationStartAt() != null && request.applicationEndAt() != null
				&& request.applicationEndAt().isBefore(request.applicationStartAt())) {
			throw new CustomException(ErrorCode.INVALID_APPLICATION_PERIOD);
		}
	}

	private <T> List<T> safe(List<T> values) {
		return values == null ? List.of() : values;
	}

	public record SavedAggregate(
			Long scholarshipId,
			Long rawScholarshipId,
			int conditionCount,
			int conditionRefCount,
			int documentCount,
			String title
	) {
	}
}
