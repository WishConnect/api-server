package com.wishconnect.domain.inquiry.repository;

import com.wishconnect.domain.inquiry.entity.ContentInquiry;
import com.wishconnect.domain.inquiry.entity.ContentInquiryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentInquiryRepository extends JpaRepository<ContentInquiry, Long> {
	Page<ContentInquiry> findAllByOrderByIdDesc(Pageable pageable);
	Page<ContentInquiry> findAllByStatusOrderByIdDesc(ContentInquiryStatus status, Pageable pageable);
}
