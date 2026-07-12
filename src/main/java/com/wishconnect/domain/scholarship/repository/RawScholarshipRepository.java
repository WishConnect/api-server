package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/*
외부 API 원본 데이터(raw_scholarship)를 저장하고 조회하는 Repository입니다.
source + sourceId 조합으로 이미 수집한 원본인지 확인해 중복 저장을 막습니다.
 */
public interface RawScholarshipRepository extends JpaRepository<RawScholarship, Long> {

	Optional<RawScholarship> findBySourceAndSourceId(String source, String sourceId);

	long countByScholarship(Scholarship scholarship);
}
