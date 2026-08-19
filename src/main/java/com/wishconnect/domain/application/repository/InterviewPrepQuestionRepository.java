package com.wishconnect.domain.application.repository;

import com.wishconnect.domain.application.entity.InterviewPrepQuestion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/*
면접 예상 질문(interview_prep_question) Repository 입니다. 장학금 단위로 캐시해 재사용합니다.
 */
public interface InterviewPrepQuestionRepository extends JpaRepository<InterviewPrepQuestion, Long> {

	List<InterviewPrepQuestion> findByScholarship_IdOrderByDisplayOrderAsc(Long scholarshipId);

	boolean existsByScholarship_Id(Long scholarshipId);

	/** 관리자 재생성용. 공고가 바뀌었을 때 지우고 다시 만든다. */
	void deleteByScholarship_Id(Long scholarshipId);
}
