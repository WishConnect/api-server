package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
정제된 장학금 데이터(scholarship)를 저장하고 조회하는 Repository입니다.
raw_scholarship 파싱 단계가 붙으면 최종 서비스용 장학금 데이터를 이 Repository로 관리합니다.
 */
public interface ScholarshipRepository extends JpaRepository<Scholarship, Long> {

	Optional<Scholarship> findByDedupKey(String dedupKey);

	// 키워드 없을 때
	@Query("SELECT s FROM Scholarship s " +
			"WHERE s.active = true " +
			"AND s.deletedAt IS NULL " +
			"AND (:category IS NULL OR s.scholarshipType = :category)")
	Page<Scholarship> findAllWithoutKeyword(
			@Param("category") String category,
			Pageable pageable
	);

	// 키워드 있을 때
	@Query("SELECT s FROM Scholarship s " +
			"WHERE s.active = true " +
			"AND s.deletedAt IS NULL " +
			"AND (s.title LIKE CONCAT('%', :keyword, '%') " +
			"     OR s.provider LIKE CONCAT('%', :keyword, '%')) " +
			"AND (:category IS NULL OR s.scholarshipType = :category)")
	Page<Scholarship> searchByKeyword(
			@Param("keyword") String keyword,
			@Param("category") String category,
			Pageable pageable
	);

	// 스크랩 필터 + 키워드 없을 때
	@Query("SELECT s FROM Scrap sc JOIN sc.scholarship s " +
			"WHERE sc.user.id = :userId " +
			"AND s.active = true AND s.deletedAt IS NULL " +
			"AND (:category IS NULL OR s.scholarshipType = :category)")
	Page<Scholarship> findScrappedByUser(
			@Param("userId") UUID userId,
			@Param("category") String category,
			Pageable pageable
	);

	// 스크랩 필터 + 키워드 있을 때
	@Query("SELECT s FROM Scrap sc JOIN sc.scholarship s " +
			"WHERE sc.user.id = :userId " +
			"AND s.active = true AND s.deletedAt IS NULL " +
			"AND (s.title LIKE CONCAT('%', :keyword, '%') " +
			"     OR s.provider LIKE CONCAT('%', :keyword, '%')) " +
			"AND (:category IS NULL OR s.scholarshipType = :category)")
	Page<Scholarship> searchScrappedByUserAndKeyword(
			@Param("userId") UUID userId,
			@Param("keyword") String keyword,
			@Param("category") String category,
			Pageable pageable
	);

	/** 추천/큐레이팅 대상: 특정 모집 상태의 활성(삭제 안 된) 장학금 전체. */
	List<Scholarship> findAllByRecruitmentStatusAndActiveTrueAndDeletedAtIsNull(RecruitmentStatus recruitmentStatus);

	/**
	 * 추천/큐레이팅 대상 + 마감 가드: 마감일이 지난 공고는 상태값이 갱신되기 전이라도 제외한다.
	 * (피드에서 사라져 sync가 재방문하지 못한 좀비 OPEN 공고 방어)
	 */
	@Query("""
			select s from Scholarship s
			where s.recruitmentStatus = :status
			  and s.active = true
			  and s.deletedAt is null
			  and (s.applicationEndAt is null or s.applicationEndAt >= :now)
			""")
	List<Scholarship> findAllOpenForRecommendation(@Param("status") RecruitmentStatus status,
												   @Param("now") LocalDateTime now);

	/** 알림 배치용: 특정 기간 안에 마감되는 활성 공고를 조회한다. */
	@Query("""
			select s from Scholarship s
			where s.recruitmentStatus = com.wishconnect.domain.scholarship.entity.RecruitmentStatus.OPEN
			  and s.active = true
			  and s.deletedAt is null
			  and s.applicationEndAt >= :start
			  and s.applicationEndAt < :end
			""")
	List<Scholarship> findOpenByApplicationEndAtBetween(@Param("start") LocalDateTime start,
														@Param("end") LocalDateTime end);

	/** 배치용: 마감일이 지났는데 CLOSED가 아닌 공고를 일괄 마감 처리한다. 처리 건수 반환. */
	@Modifying(clearAutomatically = true)
	@Query("""
			update Scholarship s
			set s.recruitmentStatus = com.wishconnect.domain.scholarship.entity.RecruitmentStatus.CLOSED,
			    s.active = false
			where s.recruitmentStatus <> com.wishconnect.domain.scholarship.entity.RecruitmentStatus.CLOSED
			  and s.applicationEndAt is not null
			  and s.applicationEndAt < :now
			""")
	int closeExpired(@Param("now") LocalDateTime now);

}
