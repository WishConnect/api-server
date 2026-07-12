package com.wishconnect.domain.scholarship.entity;

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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
장학금 신청에 필요한 제출 서류 엔티티입니다.
자기소개서/학업계획서처럼 작성 진행률을 추적할 서류는 isEssay=true로 구분합니다.
 */
@Getter
@Entity
@Table(name = "scholarship_document")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScholarshipDocument extends BaseCreatedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "scholarship_id", nullable = false)
	private Scholarship scholarship;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(name = "is_essay", nullable = false)
	private boolean essay;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@Builder
	private ScholarshipDocument(
		Scholarship scholarship,
		String name,
		boolean essay,
		int displayOrder
	) {
		this.scholarship = scholarship;
		this.name = name;
		this.essay = essay;
		this.displayOrder = displayOrder;
	}
}
