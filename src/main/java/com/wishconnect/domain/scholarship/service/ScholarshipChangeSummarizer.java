package com.wishconnect.domain.scholarship.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 감사 로그 detail 에 "무엇이 바뀌었는지" 를 붙인다. 전·후 값 전체는 before_json/after_json 에 이미 남지만,
 * 목록에서 한 줄로 보이지 않아 "모집 상태가 바뀌었는지" 를 알 수 없었다(QA 6.3).
 */
@Component
@RequiredArgsConstructor
public class ScholarshipChangeSummarizer {

	private final ObjectMapper objectMapper;

	/** @param before ScholarshipAdminSnapshot 또는 AdminScholarshipEditSnapshot */
	public String summarize(Object before, Object after) {
		try {
			JsonNode from = objectMapper.valueToTree(before);
			JsonNode to = objectMapper.valueToTree(after);
			return ScholarshipChangeFields.summarize(ScholarshipChangeFields.kindOf(from), from, to);
		} catch (RuntimeException e) {
			// 요약은 부가 정보다. 실패해도 감사 기록 자체는 남겨야 한다.
			return "변경 요약 실패";
		}
	}
}
