package com.wishconnect.domain.application.repository;

import com.wishconnect.domain.application.entity.EssayQuestion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EssayQuestionRepository extends JpaRepository<EssayQuestion, Long> {

	/** 특정 지원서의 문항 목록을 순서대로 조회. */
	List<EssayQuestion> findByEssay_IdOrderByQuestionOrderAsc(Long essayId);

	/** 소유 essay 확인용 단건 조회. */
	Optional<EssayQuestion> findByIdAndEssay_Id(Long id, Long essayId);

	/** 지원서에 속한 문항 수. */
	long countByEssay_Id(Long essayId);
}
