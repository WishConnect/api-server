package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.config.ScholarshipApiProperties;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ScholarshipMapperTest {
	@Test
	void kosafClosingDateRemainsAvailableUntilTheNextDay() {
		var item = new ObjectMapper().createObjectNode()
				.put("상품명", "아산재단 장학금").put("모집종료일", "20261026");
		var mapper = new ScholarshipMapper();
		var properties = new ScholarshipApiProperties(null, null, null, null, "key", "KOSAF_SCHOLARSHIP", null, 1, 1);

		assertThat(mapper.isClosed(item, LocalDate.of(2026, 10, 26))).isFalse();
		assertThat(mapper.isClosed(item, LocalDate.of(2026, 10, 27))).isTrue();
		assertThat(mapper.toScholarship(item, null, properties).getApplicationEndAt())
				.isEqualTo("2026-10-26T23:59:59");
	}
}
