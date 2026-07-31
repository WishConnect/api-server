package com.wishconnect.domain.common.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.Arrays;
import org.springframework.util.StringUtils;

/**
 * 전공 계열. 대학알리미 학과 정보 API 의 대계열명(korSrsLclftNm) 6종을 그대로 따른다.
 *
 * <p>DB 에는 한글 표기 그대로 저장한다({@link MajorCategoryConverter}).
 * 마스터 동기화로 이미 한글 값이 들어가 있고, 운영은 {@code ddl-auto: validate} 라
 * 컬럼 값을 영문 상수명으로 바꾸면 기존 데이터를 전부 마이그레이션해야 하기 때문이다.
 * API 응답도 {@link JsonValue} 로 한글 표기를 내보내 프론트 노출값과 일치시킨다.
 */
public enum MajorCategory {

	HUMANITIES_SOCIAL("인문사회계열"),
	ENGINEERING("공학계열"),
	NATURAL_SCIENCE("자연과학계열"),
	ARTS_AND_SPORTS("예체능계열"),
	MEDICAL("의학계열"),
	INTERDISCIPLINARY("광역계열");

	private final String label;

	MajorCategory(String label) {
		this.label = label;
	}

	@JsonValue
	public String getLabel() {
		return label;
	}

	/**
	 * 한글 표기 또는 영문 상수명으로 계열을 찾는다. 해당 값이 없으면 null.
	 * (외부 공공데이터에 예상 밖의 계열명이 섞여 들어와도 동기화 자체는 계속되도록 예외를 던지지 않는다)
	 */
	public static MajorCategory from(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String normalized = value.trim();
		return Arrays.stream(values())
				.filter(category -> category.label.equals(normalized)
						|| category.name().equalsIgnoreCase(normalized))
				.findFirst()
				.orElse(null);
	}

	/** 사용자 입력 검증용. 6종에 없는 값이면 400 으로 막는다. */
	public static MajorCategory fromRequired(String value) {
		MajorCategory category = from(value);
		if (category == null) {
			throw new CustomException(ErrorCode.INVALID_MAJOR_CATEGORY);
		}
		return category;
	}
}
