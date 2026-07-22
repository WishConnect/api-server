package com.wishconnect.domain.scholarship.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.wishconnect.domain.archive.entity.Scrap;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/*
장학금 스크랩(scrap) Repository. 상세 화면의 isScrapped 판단과
추천 강화(Phase 2)의 행동 신호 조회에 사용합니다.
 */
public interface ScrapRepository extends JpaRepository<Scrap, Long> {

	boolean existsByUserIdAndScholarshipId(UUID userId, Long scholarshipId);

	Optional<Scrap> findByUserIdAndScholarshipId(UUID userId, Long scholarshipId);

	@Query("SELECT s.scholarship FROM Scrap s "+
			"WHERE s.user = :userId " +
			"AND s.scholarship IN :scholarshipIds"

	)
	List<Long> findScrappedScholarshipIds(@Param("userId") UUID userId,
										  @Param("scholarshipIds") List<Long> scholarshipIds);
}
