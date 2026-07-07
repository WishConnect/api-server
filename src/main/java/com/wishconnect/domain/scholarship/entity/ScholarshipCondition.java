package com.wishconnect.domain.scholarship.entity;

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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "scholarship_condition")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ScholarshipCondition extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "scholarship_id", nullable = false)
	private Scholarship scholarship;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ConditionType conditionType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Operator operator;

	/** conditionType 에 따라 참조하는 마스터 레코드 id (예: region_id/major_id). 다형이라 FK 미매핑 */
	@Column
	private Long refId;

	@Column
	private Integer valueInt;

	@Column
	private Integer valueIntMax;

	@Column
	private String valueString;

	@Column(nullable = false)
	private boolean isAutoExtracted;
}
