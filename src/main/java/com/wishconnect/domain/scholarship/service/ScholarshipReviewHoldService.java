package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.entity.MergeCandidateStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipMergeCandidateRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.util.ScholarshipAnomalies;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새로 수집된 장학금의 검수 대기(보류)를 푼다 — 선작업·후노출.
 *
 * <p>수집·파싱으로 만들어진 장학금은 보류 상태로 시작해 사용자에게 보이지 않는다
 * ({@link Scholarship#isReviewHeld()}). 다음 세 경우에 풀린다.
 * <ul>
 *   <li>일일 배치 끝 {@link #releaseCleared()}: 중복 검사를 마쳤고, 대기 중인 중복 후보가 없고, 데이터 이상이 없다.</li>
 *   <li>중복 후보 병합·반려 {@link #releaseIfClear(Long)}: 남는 쪽(반려면 양쪽)에 다른 문제가 없다.</li>
 *   <li>관리자 공개 {@link #publish(Long)}: 사람이 확인했으므로 조건 없이 푼다.</li>
 * </ul>
 * 이미 공개된 장학금은 이 서비스가 다시 숨기지 않는다. 사용자가 스크랩해 둔 공고가 사라지면 안 되기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScholarshipReviewHoldService {

	/** 보류 사유 코드. 관리자 화면 표시용. */
	public static final String REASON_DEDUP_NOT_CHECKED = "DEDUP_NOT_CHECKED";
	public static final String REASON_DUPLICATE_PENDING = "DUPLICATE_PENDING";

	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipMergeCandidateRepository mergeCandidateRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;

	/** 일일 배치 결과. 남은 건은 사유별로 센다(한 건에 사유가 여럿이면 앞선 사유 하나로만 센다). */
	public record ReleaseResult(int released, int waitingDedup, int waitingDuplicate, int waitingAnomaly) {
		public int stillHeld() {
			return waitingDedup + waitingDuplicate + waitingAnomaly;
		}
	}

	/** 배치 끝에서 문제없는 보류 건을 모두 공개한다. */
	@Transactional
	public ReleaseResult releaseCleared() {
		List<Scholarship> held = scholarshipRepository.findReviewHeld();
		Set<Long> pending = pendingCandidateIds();
		int released = 0;
		int waitingDedup = 0;
		int waitingDuplicate = 0;
		int waitingAnomaly = 0;
		for (Scholarship scholarship : held) {
			// 중복 검사를 아직 못 했다(LLM 실패·이번 배치 한도 초과). 중복이 새어 나가지 않게 기다린다.
			if (scholarship.getDedupScannedAt() == null) {
				waitingDedup++;
				continue;
			}
			if (pending.contains(scholarship.getId())) {
				waitingDuplicate++;
				continue;
			}
			if (!anomalies(scholarship).isEmpty()) {
				waitingAnomaly++;
				continue;
			}
			scholarship.releaseReviewHold();
			released++;
		}
		ReleaseResult result = new ReleaseResult(released, waitingDedup, waitingDuplicate, waitingAnomaly);
		log.info("[ReviewHold] 공개={} 대기(중복검사 전)={} 대기(중복후보)={} 대기(데이터이상)={}",
				released, waitingDedup, waitingDuplicate, waitingAnomaly);
		return result;
	}

	/**
	 * 중복 후보를 처리한 직후 호출한다. 다른 대기 후보·데이터 이상이 없으면 공개한다.
	 *
	 * <p>사람이 이 공고를 중복 관점에서 봤으므로 중복 검사 완료 여부는 따지지 않는다.
	 *
	 * @return 이번에 공개했으면 true
	 */
	@Transactional
	public boolean releaseIfClear(Long scholarshipId) {
		Scholarship scholarship = scholarshipRepository.findById(scholarshipId).orElse(null);
		if (scholarship == null || scholarship.isDeleted() || !scholarship.isReviewHeld()) {
			return false;
		}
		if (pendingCandidateIds().contains(scholarshipId) || !anomalies(scholarship).isEmpty()) {
			return false;
		}
		scholarship.releaseReviewHold();
		log.info("[ReviewHold] 중복 후보 처리 후 공개 scholarshipId={}", scholarshipId);
		return true;
	}

	/** 관리자 공개 결과. 감사 기록에 공개 전 상태를 남기기 위해 돌려준다. */
	public record PublishResult(Long scholarshipId, String title, boolean wasHeld, List<String> reasons) {
	}

	/** 관리자 공개. 남은 사유가 있어도 사람이 확인한 것으로 보고 푼다. */
	@Transactional
	public PublishResult publish(Long scholarshipId) {
		Scholarship scholarship = scholarshipRepository.findById(scholarshipId)
				.filter(found -> !found.isDeleted())
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
		boolean wasHeld = scholarship.isReviewHeld();
		List<String> reasons = holdReasons(scholarship);
		scholarship.releaseReviewHold();
		if (wasHeld) {
			log.info("[ReviewHold] 관리자 공개 scholarshipId={} 남아 있던 사유={}", scholarshipId, reasons);
		}
		return new PublishResult(scholarshipId, scholarship.getTitle(), wasHeld, reasons);
	}

	/** 아직 보류인 이유. 공개된 건은 빈 목록. 관리자 화면에서 무엇을 처리해야 풀리는지 보여 준다. */
	@Transactional(readOnly = true)
	public List<String> holdReasons(Scholarship scholarship) {
		if (!scholarship.isReviewHeld() || scholarship.isDeleted()) {
			return List.of();
		}
		List<String> reasons = new ArrayList<>();
		if (scholarship.getDedupScannedAt() == null) reasons.add(REASON_DEDUP_NOT_CHECKED);
		if (pendingCandidateIds().contains(scholarship.getId())) reasons.add(REASON_DUPLICATE_PENDING);
		reasons.addAll(anomalies(scholarship));
		return List.copyOf(reasons);
	}

	private Set<Long> pendingCandidateIds() {
		return new HashSet<>(mergeCandidateRepository.findScholarshipIdsByStatus(MergeCandidateStatus.PENDING));
	}

	private List<String> anomalies(Scholarship scholarship) {
		return ScholarshipAnomalies.of(scholarship,
				scholarshipConditionRepository.countByScholarshipId(scholarship.getId()) > 0);
	}
}
