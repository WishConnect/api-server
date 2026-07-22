package com.wishconnect.domain.common.repository;

import com.wishconnect.domain.common.entity.Major;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorRepository extends JpaRepository<Major, Long> {

	List<Major> findTop10ByNameContainingIgnoreCaseOrderByNameAsc(String keyword);

	Optional<Major> findFirstByName(String name);
}
