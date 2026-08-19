package com.wishconnect.domain.application.repository;

import com.wishconnect.domain.application.entity.EssayAnswer;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EssayAnswerRepository extends JpaRepository<EssayAnswer, Long> {

	/** 문항 ID 로 답변 단건 조회. */
	Optional<EssayAnswer> findByEssayQuestion_Id(Long essayQuestionId);

	/** 문항 교체 시 정리용. 문항이 사라지면 그 답변 레코드도 의미가 없다. */
	void deleteByEssayQuestion_Id(Long essayQuestionId);

	/** 특정 지원서의 모든 문항 답변 (③ 상세 조회 시 사용). */
	List<EssayAnswer> findByEssayQuestion_Essay_Id(Long essayId);

	/** 완료 확정된 답변 수 (진행률 계산용). */
	long countByEssayQuestion_Essay_IdAndIsCompletedTrue(Long essayId);
}
