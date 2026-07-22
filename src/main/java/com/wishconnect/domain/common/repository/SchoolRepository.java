package com.wishconnect.domain.common.repository;

import com.wishconnect.domain.common.entity.School;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolRepository extends JpaRepository<School, Long> {

	List<School> findTop10ByNameContainingIgnoreCaseOrderByNameAsc(String keyword);

	Optional<School> findFirstByName(String name);
}
