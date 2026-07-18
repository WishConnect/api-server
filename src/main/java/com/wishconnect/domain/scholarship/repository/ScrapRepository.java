package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.Scrap;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/*
장학금 스크랩(scrap) Repository. 상세 화면의 isScrapped 판단과
추천 강화(Phase 2)의 행동 신호 조회에 사용합니다.
 */
public interface ScrapRepository extends JpaRepository<Scrap, Long> {

	boolean existsByUserIdAndScholarshipId(UUID userId, Long scholarshipId);
}
