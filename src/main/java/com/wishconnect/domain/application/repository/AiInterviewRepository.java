package com.wishconnect.domain.application.repository;

import com.wishconnect.domain.application.entity.AiInterview;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiInterviewRepository extends JpaRepository<AiInterview, Long> {

	/** 문항의 인터뷰 대화 이력 (stepOrder 순, ④ 인터뷰 API에서 컨텍스트로 활용). */
	List<AiInterview> findByEssayQuestion_IdOrderByStepOrderAsc(Long essayQuestionId);

	/** 문항 교체 시 정리용. 사전 질문은 그 문항 전용이라 문항이 바뀌면 남길 수 없다. */
	void deleteByEssayQuestion_Id(Long essayQuestionId);

	/** 특정 지원서에 속한 모든 문항의 인터뷰 이력 (③ 상세 조회 시 일괄 로드용). */
	List<AiInterview> findByEssayQuestion_Essay_IdOrderByEssayQuestion_IdAscStepOrderAsc(Long essayId);
}
