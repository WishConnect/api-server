package com.wishconnect.domain.scholarship.entity;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
장학금 추천/매칭에 사용할 자격 조건 엔티티입니다.
조건 종류가 계속 늘어날 수 있어 conditionType + value 형태의 EAV 구조로 저장합니다.
 */
@Getter
@Entity
@Table(name = "scholarship_condition")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScholarshipCondition {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "scholarship_id", nullable = false)
	private Scholarship scholarship;

	@Enumerated(EnumType.STRING)
	@Column(name = "condition_type", nullable = false, length = 30)
	private ConditionType conditionType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ConditionOperator operator;

	@Column(name = "ref_id")
	private Long refId;

	/*
	조건에서 추출한 숫자값입니다.
	예: 중위소득 150% 이하 -> 150, 지원구간 5구간 이내 -> 5, 평점 2.75 이상 -> 275(평점은 100배 저장)
	 */
	@Column(name = "value_int")
	private Integer valueInt;

	// 범위 조건의 최대값입니다. 예: 대학2학기~대학8학기 -> valueInt=2, valueIntMax=8
	@Column(name = "value_int_max")
	private Integer valueIntMax;

	// 원본 조건 문장입니다. 숫자 추출을 하더라도 사람이 확인할 수 있게 항상 보존합니다.
	@Column(name = "value_string", columnDefinition = "TEXT")
	private String valueString;

	@Column(name = "is_auto_extracted", nullable = false)
	private boolean autoExtracted;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Builder
	private ScholarshipCondition(
		Scholarship scholarship,
		ConditionType conditionType,
		ConditionOperator operator,
		Long refId,
		Integer valueInt,
		Integer valueIntMax,
		String valueString,
		boolean autoExtracted
	) {
		this.scholarship = scholarship;
		this.conditionType = conditionType;
		this.operator = operator == null ? ConditionOperator.EQ : operator;
		this.refId = refId;
		this.valueInt = valueInt;
		this.valueIntMax = valueIntMax;
		this.valueString = valueString;
		this.autoExtracted = autoExtracted;
	}

	@PrePersist
	void prePersist() {
		LocalDateTime now = LocalDateTime.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	void preUpdate() {
		this.updatedAt = LocalDateTime.now();
	}
}
