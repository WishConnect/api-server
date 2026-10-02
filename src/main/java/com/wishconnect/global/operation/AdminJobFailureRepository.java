package com.wishconnect.global.operation;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminJobFailureRepository extends JpaRepository<AdminJobFailure, Long> {

	@Query("select f from AdminJobFailure f where f.jobRunId = :runId "
			+ "and (:step is null or f.step = :step) "
			+ "and (:type is null or f.failureType = :type) order by f.id asc")
	Page<AdminJobFailure> search(@Param("runId") Long runId, @Param("step") String step,
			@Param("type") AdminJobFailureType type, Pageable pageable);

	/** 실행별·단계별·유형별 건수. [jobRunId, step, failureType, count] */
	@Query("select f.jobRunId, f.step, f.failureType, count(f) from AdminJobFailure f "
			+ "where f.jobRunId in :runIds group by f.jobRunId, f.step, f.failureType")
	List<Object[]> countByRunStepType(@Param("runIds") Collection<Long> runIds);
}
