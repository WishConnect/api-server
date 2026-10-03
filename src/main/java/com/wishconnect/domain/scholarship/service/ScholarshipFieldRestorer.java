package com.wishconnect.domain.scholarship.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipDetailResponse;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipEditSnapshot;
import com.wishconnect.domain.scholarship.dto.ScholarshipAdminSnapshot;
import com.wishconnect.domain.scholarship.entity.NoticeKind;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.entity.SubmissionChannel;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.service.ScholarshipChangeFields.Kind;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그 스냅샷에서 <b>선택한 필드만</b> 장학금에 되돌린다.
 *
 * <p>예전 복구는 스냅샷 전체를 덮어써, 제목만 되돌리려다 그 사이 배치가 CLOSED 로 마감한 모집 상태까지
 * OPEN 으로 되돌렸다(2026-09 QA #725). 여기서는 현재 값을 바탕으로 고른 필드만 기록 값으로 바꾼다.
 */
@Service
@RequiredArgsConstructor
public class ScholarshipFieldRestorer {

	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipManualService scholarshipManualService;
	private final ScholarshipAdminOverviewService scholarshipAdminOverviewService;
	private final ScholarshipManualAggregateStore aggregateStore;
	private final ObjectMapper objectMapper;

	/** 지금 장학금 상태를 기록과 같은 모양의 스냅샷으로 만든다(비교·새 감사 기록용). */
	@Transactional(readOnly = true)
	public JsonNode currentSnapshot(Kind kind, Long scholarshipId) {
		if (kind == Kind.AGGREGATE) {
			return objectMapper.valueToTree(AdminScholarshipEditSnapshot.from(
					scholarshipAdminOverviewService.detail(scholarshipId)));
		}
		return objectMapper.valueToTree(ScholarshipAdminSnapshot.from(find(scholarshipId)));
	}

	/**
	 * @param recorded 감사 로그의 before 스냅샷(되돌릴 값)
	 * @param fields   되돌릴 필드. {@link ScholarshipChangeFields#fields(Kind)} 안의 값이어야 한다
	 */
	@Transactional
	public void restore(Kind kind, Long scholarshipId, JsonNode recorded, Set<String> fields) {
		if (kind == Kind.AGGREGATE) {
			restoreAggregate(scholarshipId, recorded, fields);
		} else {
			restoreAdminSnapshot(scholarshipId, recorded, fields);
		}
	}

	private void restoreAdminSnapshot(Long scholarshipId, JsonNode recorded, Set<String> fields) {
		Scholarship scholarship = find(scholarshipId);
		ObjectNode merged = objectMapper.valueToTree(ScholarshipAdminSnapshot.from(scholarship));
		for (String field : fields) {
			if (ScholarshipChangeFields.DELETED.equals(field)) {
				merged.set("deletedAt", recorded.get("deletedAt"));
			} else {
				merged.set(field, recorded.get(field));
			}
		}
		// 노출 여부(active)는 따로 고르게 하지 않는다. 삭제 여부와 모집 상태에서 정한다.
		boolean deleted = merged.hasNonNull("deletedAt");
		String status = merged.path("recruitmentStatus").asText(null);
		boolean statusChosen = fields.contains(ScholarshipChangeFields.RECRUITMENT_STATUS);
		boolean deletionChosen = fields.contains(ScholarshipChangeFields.DELETED);
		if (deleted) {
			merged.put("active", false);
		} else if (statusChosen || deletionChosen) {
			merged.put("active", !RecruitmentStatus.CLOSED.name().equals(status));
		}
		scholarshipManualService.restore(scholarshipId, read(merged, ScholarshipAdminSnapshot.class));
	}

	private void restoreAggregate(Long scholarshipId, JsonNode recorded, Set<String> fields) {
		AdminScholarshipEditSnapshot current = AdminScholarshipEditSnapshot.from(
				scholarshipAdminOverviewService.detail(scholarshipId));
		ObjectNode merged = objectMapper.valueToTree(current.scholarship());
		JsonNode recordedScholarship = recorded.path("scholarship");
		boolean scalarChosen = false;
		for (String field : fields) {
			if (ScholarshipChangeFields.CONDITIONS.equals(field) || ScholarshipChangeFields.DOCUMENTS.equals(field)) {
				continue;
			}
			merged.set(field, recordedScholarship.get(field));
			scalarChosen = true;
		}
		if (scalarChosen) {
			AdminScholarshipDetailResponse.ScholarshipData data =
					read(merged, AdminScholarshipDetailResponse.ScholarshipData.class);
			validatePeriod(data.applicationStartAt(), data.applicationEndAt());
			find(scholarshipId).restoreAggregateFields(data.title(), data.provider(), data.summary(),
					data.description(), parse(ScholarshipType.class, data.scholarshipType()),
					data.applicationStartAt(), data.applicationEndAt(),
					parse(RecruitmentStatus.class, data.recruitmentStatus()), data.selectionCount(), data.amount(),
					data.homepageUrl(), data.detailUrl(), parse(NoticeKind.class, data.noticeKind()), data.combined(),
					data.submissionMethod(), parse(SubmissionChannel.class, data.submissionChannel()),
					data.submissionEvidence(), data.contact(),
					parse(RequirementLevel.class, data.essayRequirement()), data.essayEvidence(),
					parse(RequirementLevel.class, data.interviewRequirement()), data.interviewEvidence());
		}
		if (fields.contains(ScholarshipChangeFields.CONDITIONS) || fields.contains(ScholarshipChangeFields.DOCUMENTS)) {
			AdminScholarshipEditSnapshot snapshot = read(recorded, AdminScholarshipEditSnapshot.class);
			if (fields.contains(ScholarshipChangeFields.CONDITIONS)) {
				aggregateStore.replaceConditionsFromSnapshot(scholarshipId, snapshot.conditions());
			}
			if (fields.contains(ScholarshipChangeFields.DOCUMENTS)) {
				aggregateStore.replaceDocumentsFromSnapshot(scholarshipId, snapshot.documents());
			}
		}
	}

	private Scholarship find(Long scholarshipId) {
		return scholarshipRepository.findById(scholarshipId)
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
	}

	private <T> T read(JsonNode node, Class<T> type) {
		try {
			return objectMapper.treeToValue(node, type);
		} catch (JsonProcessingException e) {
			throw new CustomException(ErrorCode.AUDIT_RESTORE_NOT_SUPPORTED, e);
		}
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String value) {
		return value == null || value.isBlank() ? null : Enum.valueOf(type, value);
	}

	private static void validatePeriod(LocalDateTime startAt, LocalDateTime endAt) {
		if (startAt != null && endAt != null && endAt.isBefore(startAt)) {
			throw new CustomException(ErrorCode.INVALID_APPLICATION_PERIOD);
		}
	}
}
