package com.wishconnect.domain.application.repository;

import com.wishconnect.domain.application.entity.Essay;
import com.wishconnect.domain.application.entity.EssayStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EssayRepository extends JpaRepository<Essay, Long> {

	/** 사용자의 지원서 목록 (아카이빙에서 활용). */
	Page<Essay> findByUser_Id(UUID userId, Pageable pageable);

	/** 사용자의 지원서를 상태로 필터링 조회. */
	Page<Essay> findByUser_IdAndStatus(UUID userId, EssayStatus status, Pageable pageable);

	/** 소유자 검증까지 포함한 지원서 조회 (권한 확인용). */
	Optional<Essay> findByIdAndUser_Id(Long id, UUID userId);

	/** (user, scholarship) UNIQUE 제약 검사용. */
	Optional<Essay> findByUser_IdAndScholarship_Id(UUID userId, Long scholarshipId);

	long countByUser_Id(UUID userId);

	long countByUser_IdAndStatus(UUID userId, EssayStatus status);
}
