package com.wishconnect.domain.scholarship.entity;

import com.wishconnect.global.common.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

/*
장학금 추천/매칭에 사용할 자격 조건 엔티티입니다.
조건 종류가 계속 늘어날 수 있어 conditionType + value 형태의 EAV 구조로 저장합니다.
 */
@Getter
@Entity
@Table(name = "scholarship_condition")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScholarshipCondition extends BaseEntity {

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

	/**
	 * 자격요건인지 우대사항인지. 게이트로 쓸 수 있는 건 {@code REQUIRED} 뿐이다.
	 *
	 * <p>기존 행은 마이그레이션에서 {@code REQUIRED} 로 채운다 — NULL 로 두면 현재 작동 중인
	 * 소득·성적·학년 게이트가 통째로 풀린다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ConditionNecessity necessity;

	/**
	 * 조건이 가리키는 마스터 값들. "기초생활수급자 또는 차상위계층" 처럼 OR 로 묶이므로 집합이다.
	 *
	 * <p>판정은 사용자 값 집합과의 <b>교집합</b>으로 한다. 여기가 비어 있으면 이 조건은
	 * 대조할 대상이 없다는 뜻이고, {@code ConditionMatcher} 는 {@code UNKNOWN} 을 낸다.
	 */
	@ElementCollection
	@CollectionTable(
			name = "scholarship_condition_ref",
			joinColumns = @JoinColumn(name = "condition_id"))
	// 목록 조회에서 조건마다 참조 조회가 따로 나가는(N+1) 것을 막는다.
	@BatchSize(size = 200)
	private Set<ConditionRef> refs = new LinkedHashSet<>();

	/** @deprecated {@link #refs} 로 대체됨. 값이 채워진 적이 없어 읽는 곳도 없다. */
	@Deprecated
	@Column(name = "legacy_ref_id")
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

	@Builder
	private ScholarshipCondition(
		Scholarship scholarship,
		ConditionType conditionType,
		ConditionOperator operator,
		Long refId,
		ConditionNecessity necessity,
		Integer valueInt,
		Integer valueIntMax,
		String valueString,
		boolean autoExtracted
	) {
		this.scholarship = scholarship;
		this.conditionType = conditionType;
		this.operator = operator == null ? ConditionOperator.EQ : operator;
		this.refId = refId;
		this.necessity = necessity == null ? ConditionNecessity.REQUIRED : necessity;
		this.valueInt = valueInt;
		this.valueIntMax = valueIntMax;
		this.valueString = valueString;
		this.autoExtracted = autoExtracted;
	}

	/**
	 * 마스터 참조를 통째로 갈아끼운다. 재파싱은 조건을 지우고 다시 만들지만,
	 * 2단계 구조화는 기존 행에 참조만 채우므로 이 경로가 필요하다.
	 */
	public void applyRefs(java.util.Collection<ConditionRef> resolved) {
		this.refs = new LinkedHashSet<>(resolved);
	}

	/** LLM이 원문(valueString)에서 추출한 구조화 값을 반영한다. 원문은 검증용으로 보존한다. */
	public void applyExtracted(ConditionOperator operator, Integer valueInt, Integer valueIntMax) {
		if (operator != null) {
			this.operator = operator;
		}
		this.valueInt = valueInt;
		this.valueIntMax = valueIntMax;
		this.autoExtracted = true;
	}
}
