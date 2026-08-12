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

	/**
	 * 사전 인터뷰 답변을 기록한다.
	 *
	 * <p>질문을 한 번에 노출하고 부분 제출을 허용하는 방식이므로, 이미 답변한 항목도
	 * 다시 제출하면 덮어쓴다(사용자의 답변 수정 지원). 빈 값은 기존 답변을 지우지 않도록 무시한다.
	 */
	public void writeAnswer(String answerText) {
		if (answerText == null || answerText.isBlank()) {
			return;
		}
		this.answerText = answerText;
	}

	/** 답변이 채워져 있는지 여부. */
	public boolean isAnswered() {
		return answerText != null && !answerText.isBlank();
	}
}
