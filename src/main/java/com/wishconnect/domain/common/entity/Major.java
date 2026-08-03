package com.wishconnect.domain.common.entity;

import com.wishconnect.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "major")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Major extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	/** 계열. 컬럼에는 한글 표기가 그대로 들어간다({@link MajorCategoryConverter}). */
	@Convert(converter = MajorCategoryConverter.class)
	@Column
	private MajorCategory category;

	/**
	 * 계열이 비어 있는 기존 행을 채운다.
	 * 마스터에 이름만 있고 계열이 없던 전공에 사용자가 계열을 알려준 경우를 위한 보정이라,
	 * 이미 계열이 있으면 덮어쓰지 않는다.
	 */
	public void fillCategoryIfAbsent(MajorCategory category) {
		if (this.category == null) {
			this.category = category;
		}
	}
}
