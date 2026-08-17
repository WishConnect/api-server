package com.wishconnect.domain.scholarship.repository;

import com.wishconnect.domain.scholarship.entity.NoticeParseLog;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeParseLogRepository extends JpaRepository<NoticeParseLog, Long> {

	/** 한 원본의 파싱 이력. 프롬프트 개정 전후를 나란히 보기 위한 조회다. */
	List<NoticeParseLog> findByRawScholarshipIdOrderByIdDesc(Long rawScholarshipId);

	/** 최근 파싱 결과. 관리자 정확도 평가 화면의 목록이 된다. */
	List<NoticeParseLog> findAllByOrderByIdDesc(Pageable pageable);
}
