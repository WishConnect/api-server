package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipDocument;
import org.springframework.data.jpa.repository.JpaRepository;

/*
장학금 제출 서류(scholarship_document)를 저장하고, 재동기화 시 기존 서류를 교체하는 Repository입니다.
 */
public interface ScholarshipDocumentRepository extends JpaRepository<ScholarshipDocument, Long> {

	void deleteByScholarship(Scholarship scholarship);
}
