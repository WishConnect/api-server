package com.wishconnect.domain.application.entity;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "essay")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Essay extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "scholarship_id", nullable = false)
	private Scholarship scholarship;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private EssayStatus status;

	@Column
	private LocalDateTime lastEditedAt;

	/**
	 * 문항이 고정 문항인지 공고 기반 맞춤 문항인지.
	 *
	 * <p>{@link EssayQuestionSource#GENERATED} 면 문항 생성 API 가 다시 호출돼도 LLM 을 부르지 않고
	 * 현재 문항을 그대로 돌려준다. 이 값이 없으면 재호출마다 문항을 지우고 새로 만들어,
	 * 비용과 한도를 또 쓰고 questionId 까지 바뀐다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "question_source", nullable = false, length = 20)
	@Builder.Default
	private EssayQuestionSource questionSource = EssayQuestionSource.DEFAULT;

	/**
	 * 사용자가 인터뷰·답변을 수정할 때 호출. status 를 IN_PROGRESS 로 전환하고 lastEditedAt 을 갱신.
	 * <p>
	 * COMPLETED 상태에서도 IN_PROGRESS 로 되돌린다. 사용자가 완료 확정 이후에도 문항을 수정할 수 있게 하고,
	 * 수정된 문항이 다시 confirm 되면 {@link #markCompleted()} 로 재전환된다.
	 */
	public void markInProgress() {
		this.status = EssayStatus.IN_PROGRESS;
		this.lastEditedAt = LocalDateTime.now();
	}

	/** 모든 문항이 완료 확정된 시점에 자동 호출. status 를 COMPLETED 로 전환한다. */
	/** 맞춤 문항으로 교체됐음을 남긴다. 이후 생성 요청은 LLM 없이 현재 문항을 돌려준다. */
	public void markQuestionsGenerated() {
		this.questionSource = EssayQuestionSource.GENERATED;
	}

	public void markCompleted() {
		this.status = EssayStatus.COMPLETED;
		this.lastEditedAt = LocalDateTime.now();
	}
}
