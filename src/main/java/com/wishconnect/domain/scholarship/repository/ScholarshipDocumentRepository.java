package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipDocument;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/*
장학금 제출 서류(scholarship_document)를 저장하고, 재동기화 시 기존 서류를 교체하는 Repository입니다.
 */
public interface ScholarshipDocumentRepository extends JpaRepository<ScholarshipDocument, Long> {

	void deleteByScholarship(Scholarship scholarship);

	/** 상세 화면용: 제출 서류를 표시 순서대로 조회. */
	List<ScholarshipDocument> findAllByScholarshipIdOrderByDisplayOrderAsc(Long scholarshipId);
}
