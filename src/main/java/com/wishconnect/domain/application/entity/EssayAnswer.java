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
}
