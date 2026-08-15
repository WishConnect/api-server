package com.wishconnect.domain.scholarship.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.wishconnect.domain.scholarship.entity.Scrap;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
장학금 스크랩(scrap) Repository. 상세 화면의 isScrapped 판단과
추천 강화(Phase 2)의 행동 신호 조회에 사용합니다.
 */
public interface ScrapRepository extends JpaRepository<Scrap, Long> {

	boolean existsByUserIdAndScholarshipId(UUID userId, Long scholarshipId);

	Optional<Scrap> findByUserIdAndScholarshipId(UUID userId, Long scholarshipId);

	long countByUser_Id(UUID userId);

	@Query("SELECT s.scholarship.id FROM Scrap s " +
			"WHERE s.user.id = :userId " +
			"AND s.scholarship.id IN :scholarshipIds")
	List<Long> findScrappedScholarshipIds(@Param("userId") UUID userId,
										  @Param("scholarshipIds") List<Long> scholarshipIds);




}
