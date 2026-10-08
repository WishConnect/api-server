package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipTimeline;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/*
장학금 선발 일정(scholarship_timeline) Repository. 상세 화면 타임라인 조회와 관리자 통합 수정의 전체 교체에 사용합니다.
크롤링 파싱은 LLM 출처의 행만 갱신합니다.
 */
public interface ScholarshipTimelineRepository extends JpaRepository<ScholarshipTimeline, Long> {

	void deleteByScholarship(Scholarship scholarship);

	List<ScholarshipTimeline> findAllByScholarshipIdOrderByDisplayOrderAsc(Long scholarshipId);
}
