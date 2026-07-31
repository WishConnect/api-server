package com.wishconnect.domain.common.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link MajorCategory} ↔ major.category 컬럼(한글 표기) 변환기.
 * 6종에 없는 값이 이미 저장돼 있어도 조회가 실패하지 않도록 null 로 읽고 경고만 남긴다.
 */
@Slf4j
@Converter
public class MajorCategoryConverter implements AttributeConverter<MajorCategory, String> {

	@Override
	public String convertToDatabaseColumn(MajorCategory attribute) {
		return attribute == null ? null : attribute.getLabel();
	}

	@Override
	public MajorCategory convertToEntityAttribute(String dbData) {
		MajorCategory category = MajorCategory.from(dbData);
		if (category == null && dbData != null && !dbData.isBlank()) {
			log.warn("Unknown major category in DB. value={}", dbData);
		}
		return category;
	}
}
