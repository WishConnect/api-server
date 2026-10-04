package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.MergeCandidateStatus;
import com.wishconnect.domain.scholarship.entity.ScholarshipMergeCandidate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/*
중복 장학금 병합 후보 큐를 다루는 Repository 입니다.
 */
public interface ScholarshipMergeCandidateRepository
		extends JpaRepository<ScholarshipMergeCandidate, Long>, JpaSpecificationExecutor<ScholarshipMergeCandidate> {

	/** 어드민 승인 대기 목록. */
	Page<ScholarshipMergeCandidate> findByStatusOrderByIdAsc(
			MergeCandidateStatus status, Pageable pageable);

	/**
	 * 이미 올라온 쌍인지 확인한다. 상태와 무관하게 본다 —
	 * REJECTED("중복 아님") 로 판정된 쌍을 다음 배치가 또 올리면 같은 판단을 반복시키게 된다.
	 */
	boolean existsByPrimary_IdAndDuplicate_Id(Long primaryId, Long duplicateId);

	/**
	 * 어느 쪽으로든 이미 후보에 올라온 장학금 ID.
	 * 한 장학금이 여러 쌍에 동시에 올라 병합 순서에 따라 결과가 달라지는 것을 막는다.
	 */
	@org.springframework.data.jpa.repository.Query("""
			select c.primary.id from ScholarshipMergeCandidate c where c.status = :status
			union
			select c.duplicate.id from ScholarshipMergeCandidate c where c.status = :status
			""")
	List<Long> findScholarshipIdsByStatus(
			@org.springframework.data.repository.query.Param("status") MergeCandidateStatus status);

	long countByStatus(MergeCandidateStatus status);

	/**
	 * 승인·반려·반려 취소용 조회. 행을 잠가(SELECT ... FOR UPDATE) 같은 후보를 두 관리자가 동시에 처리하지 못하게 한다.
	 * 두 번째 요청은 첫 요청이 끝날 때까지 기다린 뒤 바뀐 상태를 보고 거절된다.
	 */
	@org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@org.springframework.data.jpa.repository.Query("select c from ScholarshipMergeCandidate c where c.id = :id")
	java.util.Optional<ScholarshipMergeCandidate> findForUpdate(
			@org.springframework.data.repository.query.Param("id") Long id);

	/** 이 장학금이 어느 쪽으로든 들어간 후보(최근 순). 내리기 전 "병합으로 유도" 판단에 쓴다. */
	List<ScholarshipMergeCandidate> findTop10ByPrimary_IdOrDuplicate_IdOrderByIdDesc(Long primaryId, Long duplicateId);

	/** 병합으로 내려진 쪽인지. */
	java.util.Optional<ScholarshipMergeCandidate> findFirstByDuplicate_IdAndStatusOrderByIdDesc(
			Long duplicateId, MergeCandidateStatus status);
}
