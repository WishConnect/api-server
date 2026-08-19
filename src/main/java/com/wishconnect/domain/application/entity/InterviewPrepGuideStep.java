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
 * 면접 답변 구성 가이드의 한 단계.
 *
 * <p>화면에서 {@code STEP1 강점제시 → STEP2 경험 설명 → STEP3 성장 및 활용} 처럼 흐름으로 보여준다.
 * 답변을 통째로 베끼지 않고 <b>자기 경험으로 채울 뼈대</b>를 주기 위한 것이다.
 *
 * <p>질문에 딸린 값이라 장학금 단위로 함께 캐시된다({@link InterviewPrepQuestion} 참고).
 * 사람마다 달라지는 것은 예시답변뿐이다.
 */
@Entity
@Getter
@Table(name = "interview_prep_guide_step")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class InterviewPrepGuideStep extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "prep_question_id", nullable = false)
	private InterviewPrepQuestion question;

	/** 단계 순서 (0부터). 질문 안에서 유일하다(DB 유니크 제약). */
	@Column(name = "step_order", nullable = false)
	private int stepOrder;

	/** 단계 이름. "강점제시" 처럼 짧아야 화면 배지에 들어간다. */
	@Column(nullable = false, length = 40)
	private String title;

	/** 그 단계에서 무엇을 말해야 하는지. */
	@Column(nullable = false, columnDefinition = "TEXT")
	private String description;
}
