package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
장학금 조건(scholarship_condition)을 저장하고, 재동기화 시 기존 조건을 교체하는 Repository입니다.
 */
public interface ScholarshipConditionRepository extends JpaRepository<ScholarshipCondition, Long> {

	void deleteByScholarship(Scholarship scholarship);

	/** 추천 계산용: 여러 장학금의 조건을 한 번에 조회(N+1 방지). */
	List<ScholarshipCondition> findAllByScholarshipIn(List<Scholarship> scholarships);

	/** 상세 화면용: 특정 장학금의 조건 전체. */
	List<ScholarshipCondition> findAllByScholarshipId(Long scholarshipId);

	long countByScholarshipId(Long scholarshipId);

	/** LLM 구조화 추출 대상: 아직 추출 안 됐고 수치도 비어 있는 조건. */
	List<ScholarshipCondition> findTop50ByAutoExtractedFalseAndValueIntIsNullAndConditionTypeIn(
			List<com.wishconnect.domain.scholarship.entity.ConditionType> conditionTypes);

	/**
	 * 마스터 참조 채우기 대상: 참조가 아직 하나도 없는 조건.
	 *
	 * <p>참조가 이미 있으면 건드리지 않는다. 대학공지는 LLM 이 본문을 읽고 채운 것이라
	 * 규칙 기반 스캔으로 덮어쓰면 더 나쁜 값으로 되돌린다.
	 */
	@Query("select c from ScholarshipCondition c where c.conditionType in :types and c.refs is empty")
	List<ScholarshipCondition> findRefBackfillTargets(
			@Param("types") List<com.wishconnect.domain.scholarship.entity.ConditionType> types,
			Pageable pageable);
}
