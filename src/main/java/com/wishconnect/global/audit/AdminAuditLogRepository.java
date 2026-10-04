package com.wishconnect.global.audit;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

	Page<AdminAuditLog> findAllByOrderByIdDesc(Pageable pageable);

	Page<AdminAuditLog> findAllByActionOrderByIdDesc(AdminAction action, Pageable pageable);

	/** 같은 대상의, 이 기록 이후 감사 기록(복구 미리보기의 "그 사이 변경" 감지용). */
	List<AdminAuditLog> findTop20ByTargetTypeAndTargetIdAndIdGreaterThanOrderByIdDesc(
			String targetType, Long targetId, Long id);
}
