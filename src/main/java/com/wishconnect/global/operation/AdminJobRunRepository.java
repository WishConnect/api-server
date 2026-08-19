package com.wishconnect.global.operation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminJobRunRepository extends JpaRepository<AdminJobRun, Long> {
	Page<AdminJobRun> findAllByOrderByIdDesc(Pageable pageable);
	Page<AdminJobRun> findByStatusOrderByIdDesc(AdminJobStatus status, Pageable pageable);
}
