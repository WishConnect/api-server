package com.wishconnect.global.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

	Page<AdminAuditLog> findAllByOrderByIdDesc(Pageable pageable);

	Page<AdminAuditLog> findAllByActionOrderByIdDesc(AdminAction action, Pageable pageable);
}
