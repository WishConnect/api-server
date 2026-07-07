package com.wishconnect.domain.application.entity;

import com.wishconnect.global.common.BaseCreatedEntity;
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
 * AI 인터뷰(에세이 문항별 대화형 질의응답).
 * ⚠️ 타임스탬프: 컨벤션 노트가 ai_interview 를 createdAt-only 로 명시해 BaseCreatedEntity 를 상속.
 *    (상세 필드목록엔 updatedAt 도 있어 상충 → ERD 확인 필요)
 */
@Entity
@Getter
@Table(name = "ai_interview")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AiInterview extends BaseCreatedEntity {

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
}
