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
 * AI 인터뷰(에세이 문항별 대화형 질의응답). created_at/updated_at 모두 관리한다.
 */
@Entity
@Getter
@Table(name = "ai_interview")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AiInterview extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "essay_question_id", nullable = false)
	private EssayQuestion essayQuestion;

	@Column(columnDefinition = "TEXT", nullable = false)
	private String questionText;

	@Column(columnDefinition = "TEXT")
	private String answerText;

	@Column(nullable = false)
	private int stepOrder;

	/** 사전 인터뷰에서 사용자의 답변을 기록. answerText 가 이미 채워져 있으면 아무 작업도 하지 않는다. */
	public void recordAnswer(String answerText) {
		if (this.answerText == null || this.answerText.isBlank()) {
			this.answerText = answerText;
		}
	}
}
