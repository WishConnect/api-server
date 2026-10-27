package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.MergeBulkConflictResponse;
import com.wishconnect.domain.scholarship.dto.MergeBulkConflictResponse.Conflict;
import com.wishconnect.domain.scholarship.dto.MergeBulkConflictResponse.Reason;
import com.wishconnect.domain.scholarship.dto.MergeBulkResultResponse;
import com.wishconnect.domain.scholarship.dto.MergeBulkResultResponse.Item;
import com.wishconnect.domain.scholarship.dto.MergeBulkResultResponse.Result;
import com.wishconnect.domain.scholarship.dto.MergeResultResponse;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipMergeCandidate;
import com.wishconnect.domain.scholarship.repository.ScholarshipMergeCandidateRepository;
import com.wishconnect.global.audit.AdminAction;
import com.wishconnect.global.audit.AdminAuditLogService;
import com.wishconnect.global.exception.CustomDetailException;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/*
중복 후보 여러 건을 한 번에 병합·반려하는 서비스입니다.

이 클래스는 일부러 @Transactional 을 걸지 않습니다. 후보마다 기존 단건 처리(ScholarshipDedupService.approve/reject)를
그대로 불러 <b>건마다 별도 트랜잭션</b>으로 처리합니다. 한 건이 실패해도 앞에서 끝난 병합은 남고, 뒤의 후보는 계속
진행합니다. 병합 로직(참조 옮기기·행 잠금·실패 시 PENDING 유지)은 단건 서비스에만 있고 여기서 복제하지 않습니다.

실행 전에 선택 전체를 한 번 검사합니다(사전 검사). 하나라도 걸리면 아무것도 실행하지 않고 충돌 목록을 돌려줍니다.
일부만 처리된 뒤 "왜 이건 안 됐지"를 사후에 맞추는 것보다, 처리 전에 고르는 편이 되돌릴 수 없는 병합에 안전합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScholarshipMergeBulkService {

	static final int MAX_BULK = 50;
	static final int MAX_NOTE = 500;

	private final ScholarshipMergeCandidateRepository mergeCandidateRepository;
	private final ScholarshipDedupService scholarshipDedupService;
	private final AdminAuditLogService adminAuditLogService;
	/** 사전 검사용. 후보의 장학금(지연 로딩)을 읽으려면 트랜잭션이 필요하다(open-in-view 꺼짐). */
	private final TransactionTemplate transactionTemplate;

	/** 선택한 후보를 요청 순서대로 병합한다. */
	public MergeBulkResultResponse approve(List<Long> candidateIds, UUID reviewer) {
		List<Long> ids = checkSize(candidateIds);
		Map<Long, Sides> sides = precheck(ids, true);

		List<Item> results = new ArrayList<>();
		for (int i = 0; i < ids.size(); i++) {
			Long id = ids.get(i);
			Long primaryId = sides.get(id).primaryId();
			Long duplicateId = sides.get(id).duplicateId();
			try {
				MergeResultResponse result = scholarshipDedupService.approve(id, reviewer);
				adminAuditLogService.record(reviewer, AdminAction.SCHOLARSHIP_MERGE, "SCHOLARSHIP", result.primaryId(),
						MergeAuditDetail.bulkPrefix("병합", ids.size(), i + 1) + MergeAuditDetail.merged(result));
				results.add(new Item(id, result.primaryId(), result.duplicateId(), Result.MERGED, null,
						"#" + result.duplicateId() + " 를 #" + result.primaryId() + " 로 병합했습니다."));
			} catch (CustomException e) {
				results.add(failure(id, primaryId, duplicateId, e));
			} catch (RuntimeException e) {
				log.error("[MergeBulk] 병합 중 예상하지 못한 오류 candidateId={}", id, e);
				results.add(new Item(id, primaryId, duplicateId, Result.FAILED, ErrorCode.MERGE_FAILED.name(),
						ErrorCode.MERGE_FAILED.getMessage()));
			}
			recordIfFailed(reviewer, "병합", ids.size(), i + 1, results.get(results.size() - 1));
		}
		MergeBulkResultResponse response = MergeBulkResultResponse.of(results);
		log.info("[MergeBulk] 일괄 병합 요청={} 성공={} 건너뜀={} 실패={}",
				response.total(), response.succeeded(), response.skipped(), response.failed());
		return response;
	}

	/** 선택한 후보를 요청 순서대로 "중복 아님"으로 반려한다. 같은 사유를 모두에 남긴다. */
	public MergeBulkResultResponse reject(List<Long> candidateIds, String note, UUID reviewer) {
		List<Long> ids = checkSize(candidateIds);
		String trimmed = checkNote(note);
		Map<Long, Sides> sides = precheck(ids, false);

		List<Item> results = new ArrayList<>();
		for (int i = 0; i < ids.size(); i++) {
			Long id = ids.get(i);
			Long primaryId = sides.get(id).primaryId();
			Long duplicateId = sides.get(id).duplicateId();
			try {
				MergeResultResponse result = scholarshipDedupService.reject(id, reviewer, trimmed);
				adminAuditLogService.record(reviewer, AdminAction.SCHOLARSHIP_MERGE_REJECT, "SCHOLARSHIP",
						result.primaryId(), MergeAuditDetail.bulkPrefix("중복 아님", ids.size(), i + 1)
								+ MergeAuditDetail.rejected(id, result, trimmed));
				results.add(new Item(id, result.primaryId(), result.duplicateId(), Result.REJECTED, null,
						"중복 아님으로 반려했습니다."));
			} catch (CustomException e) {
				results.add(failure(id, primaryId, duplicateId, e));
			} catch (RuntimeException e) {
				log.error("[MergeBulk] 반려 중 예상하지 못한 오류 candidateId={}", id, e);
				results.add(new Item(id, primaryId, duplicateId, Result.FAILED, ErrorCode.INTERNAL_SERVER_ERROR.name(),
						ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
			}
			recordIfFailed(reviewer, "중복 아님", ids.size(), i + 1, results.get(results.size() - 1));
		}
		MergeBulkResultResponse response = MergeBulkResultResponse.of(results);
		log.info("[MergeBulk] 일괄 반려 요청={} 성공={} 건너뜀={} 실패={}",
				response.total(), response.succeeded(), response.skipped(), response.failed());
		return response;
	}

	/**
	 * 실패한 후보를 작업 기록에 남긴다(MERGE_CANDIDATE_FAILED). 그 건은 트랜잭션이 되돌아가 바뀐 것이 없다.
	 * 일괄 요청은 200 으로 끝나므로 실패한 요청 기록(ADMIN_REQUEST_FAILED)에는 잡히지 않는다. 건너뜀(선처리)은 남기지 않는다.
	 */
	private void recordIfFailed(UUID reviewer, String verb, int total, int position, Item item) {
		if (item.result() != Result.FAILED) {
			return;
		}
		adminAuditLogService.record(reviewer, AdminAction.MERGE_CANDIDATE_FAILED, "SCHOLARSHIP", item.primaryId(),
				MergeAuditDetail.bulkPrefix(verb, total, position) + "중복 후보 %d (%d vs %d) 실패: %s · %s".formatted(
						item.candidateId(), item.primaryId(), item.duplicateId(), item.errorCode(), item.message()));
	}

	/**
	 * 실행 중 실패. 사전 검사 뒤 다른 관리자가 먼저 처리해 승인 대기가 아니게 된 건은 SKIPPED, 나머지는 FAILED.
	 * 실패한 건은 단건 처리와 마찬가지로 트랜잭션이 되돌아가 아무것도 바뀌지 않는다.
	 */
	private Item failure(Long id, Long primaryId, Long duplicateId, CustomException e) {
		ErrorCode code = e.getErrorCode();
		if (code == ErrorCode.MERGE_CANDIDATE_NOT_PENDING) {
			return new Item(id, primaryId, duplicateId, Result.SKIPPED, code.name(),
					"다른 관리자가 먼저 처리해 건너뛰었습니다.");
		}
		log.warn("[MergeBulk] 후보 처리 실패 candidateId={} code={}", id, code);
		return new Item(id, primaryId, duplicateId, Result.FAILED, code.name(), code.getMessage());
	}

	private static List<Long> checkSize(List<Long> candidateIds) {
		if (candidateIds == null || candidateIds.isEmpty() || candidateIds.size() > MAX_BULK
				|| candidateIds.stream().anyMatch(java.util.Objects::isNull)) {
			throw new CustomException(ErrorCode.MERGE_BULK_SIZE_INVALID);
		}
		return List.copyOf(candidateIds);
	}

	private static String checkNote(String note) {
		if (note == null || note.isBlank()) {
			throw new CustomException(ErrorCode.MERGE_BULK_NOTE_REQUIRED);
		}
		String trimmed = note.trim();
		if (trimmed.length() > MAX_NOTE) {
			throw new CustomException(ErrorCode.MERGE_BULK_NOTE_TOO_LONG);
		}
		return trimmed;
	}

	/**
	 * 사전 검사. 걸린 것이 있으면 충돌 목록과 함께 400 을 던지고, 없으면 후보별 유지·내릴 쪽 ID 를 돌려준다.
	 *
	 * <p>잠금 없이 읽는다. 검사와 실행 사이에 다른 관리자가 처리한 후보는 실행 단계의 행 잠금(단건 approve/reject 의
	 * SELECT ... FOR UPDATE)에서 승인 대기가 아님이 드러나 SKIPPED 로 남는다.
	 */
	private Map<Long, Sides> precheck(List<Long> ids, boolean merge) {
		return transactionTemplate.execute(status -> {
			Map<Long, ScholarshipMergeCandidate> found = load(ids);
			List<Conflict> conflicts = commonConflicts(ids, found);
			if (merge) {
				conflicts.addAll(mergeConflicts(ids, found));
			}
			throwIfConflicts(conflicts);
			Map<Long, Sides> sides = new HashMap<>();
			found.forEach((id, candidate) -> sides.put(id,
					new Sides(candidate.getPrimary().getId(), candidate.getDuplicate().getId())));
			return sides;
		});
	}

	private record Sides(Long primaryId, Long duplicateId) {
	}

	private Map<Long, ScholarshipMergeCandidate> load(List<Long> ids) {
		Map<Long, ScholarshipMergeCandidate> found = new HashMap<>();
		for (ScholarshipMergeCandidate candidate : mergeCandidateRepository.findAllById(new LinkedHashSet<>(ids))) {
			found.put(candidate.getId(), candidate);
		}
		return found;
	}

	/** 병합·반려 공통: 없는 후보, 승인 대기가 아닌 후보, 같은 ID 중복. */
	private static List<Conflict> commonConflicts(List<Long> ids, Map<Long, ScholarshipMergeCandidate> found) {
		List<Conflict> conflicts = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		Set<Long> reportedDuplicateIds = new HashSet<>();
		for (Long id : ids) {
			if (!seen.add(id)) {
				if (reportedDuplicateIds.add(id)) {
					conflicts.add(new Conflict(id, sides(found.get(id)), Reason.DUPLICATE_ID,
							"후보 #" + id + " 을(를) 두 번 선택했습니다."));
				}
				continue;
			}
			ScholarshipMergeCandidate candidate = found.get(id);
			if (candidate == null) {
				conflicts.add(new Conflict(id, List.of(), Reason.NOT_FOUND, "후보 #" + id + " 이(가) 없습니다."));
			} else if (!candidate.isPending()) {
				conflicts.add(new Conflict(id, sides(candidate), Reason.NOT_PENDING,
						"후보 #" + id + " 은(는) 이미 처리됐습니다(" + candidate.getStatus().name() + ")."));
			}
		}
		return conflicts;
	}

	/**
	 * 병합만: 같은 장학금을 두 번 내리기, 연쇄 병합, 이미 내려진 장학금.
	 *
	 * <p>연쇄 병합(A←B, B←C)을 순서대로 처리하면 둘째 병합이 이미 내린 B 쪽으로 C 를 옮긴다. 사용자 데이터가
	 * 목록에서 사라진 장학금에 붙게 되므로 한 요청 안에서 섞지 않게 한다.
	 */
	private static List<Conflict> mergeConflicts(List<Long> ids, Map<Long, ScholarshipMergeCandidate> found) {
		List<ScholarshipMergeCandidate> selected = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (Long id : ids) {
			ScholarshipMergeCandidate candidate = found.get(id);
			if (candidate != null && candidate.isPending() && seen.add(id)) {
				selected.add(candidate);
			}
		}
		Map<Long, List<Long>> dropCount = new HashMap<>();
		Map<Long, List<Long>> keepBy = new HashMap<>();
		for (ScholarshipMergeCandidate candidate : selected) {
			dropCount.computeIfAbsent(candidate.getDuplicate().getId(), k -> new ArrayList<>()).add(candidate.getId());
			keepBy.computeIfAbsent(candidate.getPrimary().getId(), k -> new ArrayList<>()).add(candidate.getId());
		}

		List<Conflict> conflicts = new ArrayList<>();
		for (ScholarshipMergeCandidate candidate : selected) {
			Long id = candidate.getId();
			Scholarship primary = candidate.getPrimary();
			Scholarship duplicate = candidate.getDuplicate();
			List<Long> sameDrop = dropCount.get(duplicate.getId());
			if (sameDrop.size() > 1) {
				conflicts.add(new Conflict(id, List.of(duplicate.getId()), Reason.SAME_DUPLICATE,
						"장학금 #" + duplicate.getId() + " 이(가) 후보 " + joinIds(sameDrop)
								+ " 에서 모두 내릴 쪽입니다. 하나만 고르세요."));
			}
			List<Long> keptElsewhere = keepBy.get(duplicate.getId());
			if (keptElsewhere != null) {
				conflicts.add(new Conflict(id, List.of(duplicate.getId()), Reason.CHAINED_MERGE,
						"장학금 #" + duplicate.getId() + " 이(가) 이 후보에서는 내릴 쪽, 후보 " + joinIds(keptElsewhere)
								+ " 에서는 유지 쪽입니다. 따로 나눠 처리하세요."));
			}
			// 연쇄의 반대편(이 후보의 유지 쪽이 다른 선택 후보의 내릴 쪽)도 같이 표시해 두 카드를 모두 강조한다.
			List<Long> droppedElsewhere = dropCount.get(primary.getId());
			if (droppedElsewhere != null) {
				conflicts.add(new Conflict(id, List.of(primary.getId()), Reason.CHAINED_MERGE,
						"장학금 #" + primary.getId() + " 이(가) 이 후보에서는 유지 쪽, 후보 " + joinIds(droppedElsewhere)
								+ " 에서는 내릴 쪽입니다. 따로 나눠 처리하세요."));
			}
			List<Long> deleted = new ArrayList<>();
			if (primary.isDeleted()) deleted.add(primary.getId());
			if (duplicate.isDeleted()) deleted.add(duplicate.getId());
			if (!deleted.isEmpty()) {
				conflicts.add(new Conflict(id, deleted, Reason.SCHOLARSHIP_DELETED,
						"장학금 " + joinIds(deleted) + " 은(는) 이미 내려졌습니다. 병합하면 내린 장학금에 데이터가 붙습니다."));
			}
		}
		return conflicts;
	}

	private static void throwIfConflicts(List<Conflict> conflicts) {
		if (!conflicts.isEmpty()) {
			throw new CustomDetailException(ErrorCode.MERGE_BULK_CONFLICT, new MergeBulkConflictResponse(conflicts));
		}
	}

	private static List<Long> sides(ScholarshipMergeCandidate candidate) {
		return candidate == null ? List.of()
				: List.of(candidate.getPrimary().getId(), candidate.getDuplicate().getId());
	}

	private static String joinIds(List<Long> ids) {
		return String.join(", ", ids.stream().map(id -> "#" + id).toList());
	}
}
