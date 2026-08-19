package com.wishconnect.domain.application.repository;

import com.wishconnect.domain.application.entity.InterviewPrepSampleAnswer;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/*
면접 예시답변(interview_prep_sample_answer) Repository 입니다. 지원서 단위로 저장합니다.
 */
public interface InterviewPrepSampleAnswerRepository
		extends JpaRepository<InterviewPrepSampleAnswer, Long> {

	List<InterviewPrepSampleAnswer> findByEssay_Id(Long essayId);

	/** 자소서를 고쳐 다시 만들 때 정리용. */
	void deleteByEssay_Id(Long essayId);
}
