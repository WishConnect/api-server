package com.wishconnect.domain.scholarship.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualFullRequest;
import com.wishconnect.domain.scholarship.entity.ConditionRef;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.entity.ScholarshipDocument;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 수기 통합 등록의 DB 작업을 한 트랜잭션으로 묶는다. 외부 이미지 다운로드는 포함하지 않는다. */
@Service
@RequiredArgsConstructor
public class ScholarshipManualAggregateStore {

	private final ScholarshipRepository scholarshipRepository;
	private final RawScholarshipRepository rawScholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final ScholarshipDocumentRepository scholarshipDocumentRepository;
	private final ConditionRefResolver conditionRefResolver;
	private final ObjectMapper objectMapper;

	@Transactional
	public SavedAggregate create(ScholarshipManualFullRequest request) {
		validatePeriod(request);
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
		return saved(request, scholarship, raw.getId(), refCount, documentCount);
	}

	/** 실패 원본을 사람이 구조화해 기존 raw 행과 새 scholarship을 연결한다. */
	@Transactional
	public SavedAggregate createFromRaw(Long rawId, ScholarshipManualFullRequest request) {
		validatePeriod(request);
		RawScholarship raw = rawScholarshipRepository.findById(rawId)
				.orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT));
		if (raw.getScholarship() != null) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		Scholarship scholarship = createScholarship(request, "MANUAL_RAW:" + rawId);
		raw.markParsed(scholarship);
		int refCount = saveConditions(scholarship, safe(request.conditions()));
		int documentCount = saveDocuments(scholarship, safe(request.documents()));
		return saved(request, scholarship, raw.getId(), refCount, documentCount);
	}

	/** 통합 편집은 조건·서류 목록을 화면에 보이는 최종 상태로 교체한다. */
	@Transactional
	public SavedAggregate update(Long scholarshipId, ScholarshipManualFullRequest request) {
		validatePeriod(request);
		Scholarship scholarship = scholarshipRepository.findById(scholarshipId)
				.filter(value -> !value.isDeleted())
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
		scholarship.replaceByAdmin(
				request.title().trim(), request.provider(), request.summary(), request.description(),
				request.scholarshipType(), request.applicationStartAt(), request.applicationEndAt(),
				request.recruitmentStatus(), request.selectionCount(), request.amount(), request.homepageUrl(),
				request.detailUrl(), request.noticeKind(), request.combined(), request.submissionMethod(),
				request.submissionChannel(), request.submissionEvidence(), request.contact(),
				request.essayRequirement(), request.essayEvidence(), request.interviewRequirement(),
				request.interviewEvidence());

		scholarshipConditionRepository.deleteByScholarship(scholarship);
		scholarshipDocumentRepository.deleteByScholarship(scholarship);
		scholarshipConditionRepository.flush();
		scholarshipDocumentRepository.flush();
		int refCount = saveConditions(scholarship, safe(request.conditions()));
		int documentCount = saveDocuments(scholarship, safe(request.documents()));
		return saved(request, scholarship, null, refCount, documentCount);
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
