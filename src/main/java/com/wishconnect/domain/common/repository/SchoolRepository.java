package com.wishconnect.domain.common.repository;

import com.wishconnect.domain.common.entity.School;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SchoolRepository extends JpaRepository<School, Long> {

	List<School> findTop10ByNameContainingIgnoreCaseOrderByNameAsc(String keyword);

	Optional<School> findFirstByName(String name);

	/** 동기화 중복 판정용. 엔티티 전체를 올리지 않으려고 이름만 조회한다. */
	@Query("select s.name from School s")
	List<String> findAllNames();
}
