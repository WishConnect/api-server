package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.config.ScholarshipApiProperties;
import java.time.LocalDateTime;
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

	@Test
	void nextKosafSyncKeepsClockVerifiedOnSameDeadlineDate() {
		var item = new ObjectMapper().createObjectNode()
				.put("상품명", "아산재단 장학금").put("모집종료일", "20261026");
		var mapper = new ScholarshipMapper();
		var properties = new ScholarshipApiProperties(null, null, null, null, "key", "KOSAF_SCHOLARSHIP", null, 1, 1);
		var scholarship = mapper.toScholarship(item, null, properties);
		scholarship.refineApiDeadline(LocalDateTime.of(2026, 10, 26, 23, 59));

		assertThat(mapper.toScholarship(item, scholarship, properties).getApplicationEndAt())
				.isEqualTo("2026-10-26T23:59:00");
		item.put("모집종료일", "20261027");
		assertThat(mapper.toScholarship(item, scholarship, properties).getApplicationEndAt())
				.isEqualTo("2026-10-27T23:59:59");
	}
	@Test
	void preservesVerifiedMidnightAcrossRepeatedApiSyncs() {
		var item = new ObjectMapper().createObjectNode().put("상품명", "자정 마감").put("모집종료일", "20261215");
		var mapper = new ScholarshipMapper();
		var properties = new ScholarshipApiProperties(null, null, null, null, "key", "KOSAF_SCHOLARSHIP", null, 1, 1);
		var scholarship = mapper.toScholarship(item, null, properties);
		scholarship.refineApiDeadline(LocalDateTime.of(2026, 12, 15, 0, 0));
		scholarship.applyEnrichment("https://example.org/notice");
		for (int i = 0; i < 2; i++) mapper.toScholarship(item, scholarship, properties);
		assertThat(scholarship.getApplicationEndAt()).isEqualTo("2026-12-15T00:00:00");
		assertThat(scholarship.getEnrichedAt()).isNotNull();
	}

	@Test
	void changedApiDateRequestsAnotherEnrichmentWithoutRemovingDetailUrl() {
		var item = new ObjectMapper().createObjectNode().put("상품명", "마감 변경").put("모집종료일", "20261215");
		var mapper = new ScholarshipMapper();
		var properties = new ScholarshipApiProperties(null, null, null, null, "key", "KOSAF_SCHOLARSHIP", null, 1, 1);
		var scholarship = mapper.toScholarship(item, null, properties);
		scholarship.applyEnrichment("https://example.org/notice");
		item.put("모집종료일", "20261216");
		mapper.toScholarship(item, scholarship, properties);
		assertThat(scholarship.getEnrichedAt()).isNull();
		assertThat(scholarship.getDetailUrl()).isEqualTo("https://example.org/notice");
		assertThat(scholarship.getApplicationEndAt()).isEqualTo("2026-12-16T23:59:59");
	}

	@Test
	void verifiedClockCanBeUpdatedOnTheSameDateButManualPeriodIsProtected() {
		var scholarship = com.wishconnect.domain.scholarship.entity.Scholarship.builder()
				.applicationEndAt(LocalDateTime.of(2026, 12, 15, 18, 0)).build();
		scholarship.refineApiDeadline(LocalDateTime.of(2026, 12, 15, 17, 0));
		assertThat(scholarship.getApplicationEndAt()).isEqualTo("2026-12-15T17:00:00");
		scholarship.changePeriodLocked(true);
		scholarship.refineApiDeadline(LocalDateTime.of(2026, 12, 15, 16, 0));
		assertThat(scholarship.getApplicationEndAt()).isEqualTo("2026-12-15T17:00:00");
	}
}
