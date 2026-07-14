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

@Entity
@Getter
@Table(name = "essay_answer")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class EssayAnswer extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "essay_question_id", nullable = false)
	private EssayQuestion essayQuestion;

	@Column(columnDefinition = "TEXT")
	private String aiDraft;

	@Column(columnDefinition = "TEXT")
	private String userContent;

	@Column(nullable = false)
	private int charCount;

	@Column(nullable = false)
	private boolean isTemporary;

	@Column(nullable = false)
	private boolean isCompleted;

	/** STEP2 초안 생성 결과 반영. aiDraft 를 저장하고 userContent 에도 초기 복사한다. */
	public void applyDraft(String draft) {
		this.aiDraft = draft;
		this.userContent = draft;
		this.charCount = draft == null ? 0 : draft.length();
		this.isTemporary = true;
		this.isCompleted = false;
	}

	/** STEP2 임시저장. 사용자가 수정한 본문을 갱신한다. */
	public void updateUserContent(String userContent) {
		this.userContent = userContent;
		this.charCount = userContent == null ? 0 : userContent.length();
		this.isTemporary = true;
		this.isCompleted = false;
	}

	/** STEP2 완료 확정. userContent 는 검증된 본문(빈 문자열/글자수 초과 아님)이어야 한다. */
	public void confirm(String userContent) {
		this.userContent = userContent;
		this.charCount = userContent == null ? 0 : userContent.length();
		this.isTemporary = false;
		this.isCompleted = true;
	}
}
