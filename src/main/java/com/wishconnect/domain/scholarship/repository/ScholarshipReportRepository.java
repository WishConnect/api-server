package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.ReportStatus;
import com.wishconnect.domain.scholarship.entity.ScholarshipReport;
import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScholarshipReportRepository extends JpaRepository<ScholarshipReport, Long> {

	Page<ScholarshipReport> findAllByStatusOrderByIdDesc(ReportStatus status, Pageable pageable);

	Page<ScholarshipReport> findAllByOrderByIdDesc(Pageable pageable);

	/** 같은 사용자가 같은 장학금을 중복 신고하는 것을 막는다(미처리 건이 남아 있는 동안). */
	boolean existsByScholarship_IdAndUser_IdAndStatus(
			Long scholarshipId, UUID userId, ReportStatus status);

	List<ScholarshipReport> findAllByUser_IdOrderByIdDesc(UUID userId, Pageable pageable);
}
