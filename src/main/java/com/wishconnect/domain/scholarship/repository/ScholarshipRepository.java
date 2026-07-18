package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/*
정제된 장학금 데이터(scholarship)를 저장하고 조회하는 Repository입니다.
raw_scholarship 파싱 단계가 붙으면 최종 서비스용 장학금 데이터를 이 Repository로 관리합니다.
 */
public interface ScholarshipRepository extends JpaRepository<Scholarship, Long> {

	Optional<Scholarship> findByDedupKey(String dedupKey);

	/** 추천/큐레이팅 대상: 특정 모집 상태의 활성(삭제 안 된) 장학금 전체. */
	List<Scholarship> findAllByRecruitmentStatusAndActiveTrueAndDeletedAtIsNull(RecruitmentStatus recruitmentStatus);
}
