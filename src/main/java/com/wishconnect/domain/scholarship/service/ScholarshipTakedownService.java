package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.application.entity.EssayStatus;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.scholarship.dto.DedupScanRow;
import com.wishconnect.domain.scholarship.dto.ScholarshipAdminChangeResult;
import com.wishconnect.domain.scholarship.dto.ScholarshipAdminSnapshot;
import com.wishconnect.domain.scholarship.dto.ScholarshipDeleteCheckResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualResponse;
import com.wishconnect.domain.scholarship.entity.MergeCandidateStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipMergeCandidate;
import com.wishconnect.domain.scholarship.repository.ScholarshipMergeCandidateRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.domain.scholarship.util.ScholarshipTitleBlocker;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 관리자 장학금 내리기·복원.
 *
 * <p>내리기는 소프트 삭제다(행·사용자 데이터는 남는다). 누가·왜 내렸는지를 장학금 행에 남겨
 * 수집 배치가 다음 날 되살리지 않게 한다({@link Scholarship#isDeletedByAdmin()}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScholarshipTakedownService {

	private static final int SIMILAR_LIMIT = 5;

	private final ScholarshipRepository scholarshipRepository;
	private final ScrapRepository scrapRepository;
	private final EssayRepository essayRepository;
	private final ScholarshipMergeCandidateRepository mergeCandidateRepository;

	@Transactional(readOnly = true)
	public ScholarshipDeleteCheckResponse check(Long scholarshipId) {
		Scholarship scholarship = find(scholarshipId);
		long scraps = scrapRepository.countByScholarship_Id(scholarshipId);
		long notStarted = essayRepository.countByScholarship_IdAndStatus(scholarshipId, EssayStatus.NOT_STARTED);
		long inProgress = essayRepository.countByScholarship_IdAndStatus(scholarshipId, EssayStatus.IN_PROGRESS);
		long completed = essayRepository.countByScholarship_IdAndStatus(scholarshipId, EssayStatus.COMPLETED);

		List<ScholarshipDeleteCheckResponse.Candidate> candidates = mergeCandidateRepository
				.findTop10ByPrimary_IdOrDuplicate_IdOrderByIdDesc(scholarshipId, scholarshipId).stream()
				.map(ScholarshipTakedownService::candidate)
				.toList();
		List<ScholarshipDeleteCheckResponse.Similar> similar = similar(scholarship);

		boolean pendingOrSimilar = candidates.stream().anyMatch(c -> MergeCandidateStatus.PENDING.name().equals(c.status()))
				|| !similar.isEmpty();
		List<String> warnings = new ArrayList<>();
		if (scraps > 0) {
			warnings.add("스크랩한 사용자 " + scraps + "명의 목록에서 보이지 않게 됩니다.");
		}
		if (notStarted + inProgress + completed > 0) {
			warnings.add("자소서 " + (notStarted + inProgress + completed) + "건(작성 중 " + inProgress + ", 완료 "
					+ completed + ")이 이 장학금에 연결돼 있습니다.");
		}
		if (pendingOrSimilar) {
			warnings.add("같은 장학금으로 보이는 공고가 있습니다. 중복이라면 내리기 대신 병합하세요 — "
					+ "병합은 스크랩·자소서를 남길 쪽으로 옮기지만 내리기는 옮기지 않습니다.");
		}
		return new ScholarshipDeleteCheckResponse(scholarshipId, scholarship.getTitle(), scholarship.isDeleted(),
				scraps, notStarted, inProgress, completed, candidates, similar, pendingOrSimilar, warnings);
	}

	/** 사유 필수. 이미 내린 것은 다시 내리지 않는다(기존 사유·삭제자를 덮지 않기 위해). */
	@Transactional
	public ScholarshipAdminChangeResult delete(Long scholarshipId, UUID actorId, String reason) {
		if (!StringUtils.hasText(reason)) {
			throw new CustomException(ErrorCode.DELETE_REASON_REQUIRED);
		}
		Scholarship scholarship = find(scholarshipId);
		if (scholarship.isDeleted()) {
			throw new CustomException(ErrorCode.SCHOLARSHIP_ALREADY_DELETED);
		}
		ScholarshipAdminSnapshot before = ScholarshipAdminSnapshot.from(scholarship);
		scholarship.deleteByAdmin(actorId, reason.trim());
		log.info("[Scholarship] 관리자 내리기 (scholarshipId={}, actor={})", scholarshipId, actorId);
		return new ScholarshipAdminChangeResult(ScholarshipManualResponse.from(scholarship), before,
				ScholarshipAdminSnapshot.from(scholarship));
	}

	/** 내린 장학금 복원. 병합으로 내린 쪽은 복원하지 않는다(사용자 데이터가 이미 옮겨졌다). */
	@Transactional
	public ScholarshipAdminChangeResult restore(Long scholarshipId, UUID actorId) {
		Scholarship scholarship = find(scholarshipId);
		if (!scholarship.isDeleted()) {
			throw new CustomException(ErrorCode.SCHOLARSHIP_NOT_DELETED);
		}
		if (mergeCandidateRepository.findFirstByDuplicate_IdAndStatusOrderByIdDesc(
				scholarshipId, MergeCandidateStatus.MERGED).isPresent()) {
			throw new CustomException(ErrorCode.SCHOLARSHIP_MERGED_CANNOT_RESTORE);
		}
		ScholarshipAdminSnapshot before = ScholarshipAdminSnapshot.from(scholarship);
		scholarship.restoreByAdmin();
		log.info("[Scholarship] 관리자 복원 (scholarshipId={}, actor={})", scholarshipId, actorId);
		return new ScholarshipAdminChangeResult(ScholarshipManualResponse.from(scholarship), before,
				ScholarshipAdminSnapshot.from(scholarship));
	}

	/** 제목 묶기 키가 같은 활성 공고. 중복 탐지 배치와 같은 규칙이라 LLM 비용이 없다. */
	private List<ScholarshipDeleteCheckResponse.Similar> similar(Scholarship scholarship) {
		String key = ScholarshipTitleBlocker.blockingKey(scholarship.getTitle());
		if (key == null) {
			return List.of();
		}
		List<Long> ids = scholarshipRepository.findDedupScanRows().stream()
				.filter(row -> !Objects.equals(row.id(), scholarship.getId()))
				.filter(row -> key.equals(ScholarshipTitleBlocker.blockingKey(row.title())))
				.map(DedupScanRow::id)
				.limit(SIMILAR_LIMIT)
				.toList();
		return scholarshipRepository.findAllById(ids).stream()
				.map(value -> new ScholarshipDeleteCheckResponse.Similar(value.getId(), value.getTitle(),
						value.getProvider(),
						value.getRecruitmentStatus() == null ? null : value.getRecruitmentStatus().name(),
						value.getApplicationEndAt()))
				.toList();
	}

	private static ScholarshipDeleteCheckResponse.Candidate candidate(ScholarshipMergeCandidate value) {
		return new ScholarshipDeleteCheckResponse.Candidate(value.getId(), value.getStatus().name(),
				value.getOrigin() == null ? null : value.getOrigin().name(),
				value.getPrimary().getId(), value.getPrimary().getTitle(),
				value.getDuplicate().getId(), value.getDuplicate().getTitle());
	}

	private Scholarship find(Long scholarshipId) {
		return scholarshipRepository.findById(scholarshipId)
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
	}
}
