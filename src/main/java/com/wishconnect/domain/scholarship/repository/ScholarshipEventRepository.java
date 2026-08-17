package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.ScholarshipEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScholarshipEventRepository extends JpaRepository<ScholarshipEvent, Long> {
}
