package com.wishconnect.domain.application.repository;

import com.wishconnect.domain.application.entity.InterviewPrepQuestion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/*
면접 예상 질문(interview_prep_question) Repository 입니다. 장학금 단위로 캐시해 재사용합니다.
 */
public interface InterviewPrepQuestionRepository extends JpaRepository<InterviewPrepQuestion, Long> {

	List<InterviewPrepQuestion> findByScholarship_IdOrderByDisplayOrderAsc(Long scholarshipId);

	/**
	 * 구성 가이드까지 한 번에 읽는다.
	 *
	 * <p>가이드는 질문마다 3행이라, 그냥 조회하면 질문 수만큼 추가 쿼리가 나간다(N+1).
	 * 화면이 항상 함께 그리므로 여기서 같이 가져온다.
	 */
	@org.springframework.data.jpa.repository.Query("select distinct q from InterviewPrepQuestion q "
			+ "left join fetch q.guideSteps where q.scholarship.id = :scholarshipId "
			+ "order by q.displayOrder asc")
	List<InterviewPrepQuestion> findWithGuideByScholarshipId(
			@org.springframework.data.repository.query.Param("scholarshipId") Long scholarshipId);

	boolean existsByScholarship_Id(Long scholarshipId);

	/** 관리자 재생성용. 공고가 바뀌었을 때 지우고 다시 만든다. */
	void deleteByScholarship_Id(Long scholarshipId);
}
