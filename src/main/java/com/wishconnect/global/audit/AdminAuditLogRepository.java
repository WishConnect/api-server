package com.wishconnect.global.audit;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long>,
		JpaSpecificationExecutor<AdminAuditLog> {

	/** 작업 기록에 한 번이라도 나온 관리자(관리자 필터 목록). 로그인 실패처럼 행위자가 없는 행은 뺀다. */
	@Query("select distinct a.actorId from AdminAuditLog a where a.actorId is not null")
	List<java.util.UUID> findDistinctActorIds();

	Page<AdminAuditLog> findAllByOrderByIdDesc(Pageable pageable);

	Page<AdminAuditLog> findAllByActionOrderByIdDesc(AdminAction action, Pageable pageable);

	/** 같은 대상의, 이 기록 이후 감사 기록(복구 미리보기의 "그 사이 변경" 감지용). */
	List<AdminAuditLog> findTop20ByTargetTypeAndTargetIdAndIdGreaterThanOrderByIdDesc(
			String targetType, Long targetId, Long id);
}
