package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipTag;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScholarshipTagRepository extends JpaRepository<ScholarshipTag, Long> {

	List<ScholarshipTag> findByScholarshipOrderByDisplayOrderAsc(Scholarship scholarship);

	/** 목록 화면에서 N+1 을 피하려고 후보를 한 번에 받아온다. */
	List<ScholarshipTag> findByScholarshipInOrderByDisplayOrderAsc(Collection<Scholarship> scholarships);

	void deleteByScholarship(Scholarship scholarship);
}
