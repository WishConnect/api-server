package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.ScholarshipTimeline;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/*
장학금 선발 일정(scholarship_timeline) Repository. 상세 화면 타임라인 조회에 사용합니다.
 */
public interface ScholarshipTimelineRepository extends JpaRepository<ScholarshipTimeline, Long> {

	List<ScholarshipTimeline> findAllByScholarshipIdOrderByDisplayOrderAsc(Long scholarshipId);
}
