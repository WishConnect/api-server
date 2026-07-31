package com.wishconnect.domain.common.repository;

import com.wishconnect.domain.common.entity.Major;
import com.wishconnect.domain.common.entity.MajorCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorRepository extends JpaRepository<Major, Long> {

	List<Major> findTop10ByNameContainingIgnoreCaseOrderByNameAsc(String keyword);

	Optional<Major> findFirstByName(String name);

	/** 온보딩에서 사용자가 고른 (전공명, 계열) 조합을 그대로 찾는다. */
	Optional<Major> findFirstByNameAndCategory(String name, MajorCategory category);
}
