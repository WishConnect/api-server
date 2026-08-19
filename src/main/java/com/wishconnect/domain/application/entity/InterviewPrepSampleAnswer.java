package com.wishconnect.domain.application.entity;

import com.wishconnect.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 면접 예상 질문에 대한 예시답변.
 *
 * <p><b>질문과 달리 지원서 단위다.</b> 기획이 "작성한 자기소개서를 바탕으로" 만들도록 정했기
 * 때문에, 같은 질문이라도 사람마다 답이 달라야 한다. 질문·의도·Tip·구성가이드는 누가 보든
 * 같은 내용이라 장학금 단위로 공유하고, 여기만 개인화한다.
 *
 * <p>자소서를 받지 않는 장학금(essay NOT_REQUIRED + interview REQUIRED)에는 이 행이 없다.
 * 화면은 예시답변만 빼고 나머지를 보여주면 된다.
 */
@Entity
@Getter
@Table(name = "interview_prep_sample_answer")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class InterviewPrepSampleAnswer extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "essay_id", nullable = false)
	private Essay essay;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "prep_question_id", nullable = false)
	private InterviewPrepQuestion question;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String content;
}
