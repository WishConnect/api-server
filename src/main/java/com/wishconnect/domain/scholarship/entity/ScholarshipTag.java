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

/**
 * 장학금 분류 태그.
 *
 * <p>공공데이터 원문의 분류 필드(학자금유형구분·상품구분·운영기관구분 등)에서 뽑아낸다.
 * 지금까지 응답의 {@code tags} 는 <b>전 건 빈 배열</b>이었다(코드에 {@code List.of()} 하드코딩).
 *
 * <p>정규화 테이블로 둔 이유: 나중에 "성적우수 장학금만" 같은 태그 필터를 붙일 때
 * 콤마 문자열 컬럼이면 LIKE 로 긁어야 하고 부분일치 오탐이 난다.
 */
@Entity
@Table(name = "scholarship_tag")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScholarshipTag extends BaseCreatedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "scholarship_id", nullable = false)
	private Scholarship scholarship;

	@Column(nullable = false, length = 50)
	private String name;

	/** 화면 노출 순서. 원문 필드 우선순위대로 매긴다. */
	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@Builder
	private ScholarshipTag(Scholarship scholarship, String name, int displayOrder) {
		this.scholarship = scholarship;
		this.name = name;
		this.displayOrder = displayOrder;
	}
}
