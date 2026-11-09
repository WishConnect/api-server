package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.ScholarshipPipelineRun;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScholarshipPipelineRunRepository extends JpaRepository<ScholarshipPipelineRun, UUID> {
    List<ScholarshipPipelineRun> findAllByOrderByCreatedAtDesc(Pageable pageable);
    boolean existsByInputHashAndStatus(String hash, String status);
}
