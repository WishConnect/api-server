package com.wishconnect.global.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 작업 기록 목록 필터: 빈 조건은 걸지 않고, 한국 날짜를 서버 시간대(운영 UTC)로 바꿔 비교한다. */
class AdminAuditLogSearchTest {

	@Test
	@DisplayName("KST 날짜의 0시를 서버 시간대로 바꾼다 — 운영(UTC)에서는 전날 15시")
	void convertsKoreanDateToServerTime() {
		assertThat(AdminAuditLogSearch.serverTime(LocalDate.of(2026, 10, 27), ZoneId.of("UTC")))
				.isEqualTo(LocalDateTime.of(2026, 10, 26, 15, 0));
		assertThat(AdminAuditLogSearch.serverTime(LocalDate.of(2026, 10, 27), ZoneId.of("Asia/Seoul")))
				.isEqualTo(LocalDateTime.of(2026, 10, 27, 0, 0));
	}

	@Test
	@DisplayName("기간은 시작일 0시 이상, 종료일 다음 날 0시 미만(종료일 포함)")
	@SuppressWarnings({"unchecked", "rawtypes"})
	void periodIsInclusive() {
		Root root = mock(Root.class, RETURNS_MOCKS);
		Path createdAt = mock(Path.class);
		given(root.get("createdAt")).willReturn(createdAt);
		CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_MOCKS);

		new AdminAuditLogSearch(AdminAction.SCHOLARSHIP_MERGE, UUID.randomUUID(), "SCHOLARSHIP", 725L,
				LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 27))
				.toSpecification(ZoneId.of("UTC")).toPredicate(root, mock(CriteriaQuery.class), cb);

		verify(cb).greaterThanOrEqualTo(createdAt, LocalDateTime.of(2026, 9, 30, 15, 0));
		verify(cb).lessThan(createdAt, LocalDateTime.of(2026, 10, 27, 15, 0));
	}

	@Test
	@DisplayName("비어 있는 조건은 걸지 않는다")
	@SuppressWarnings({"unchecked", "rawtypes"})
	void emptyFiltersAddNothing() {
		Root root = mock(Root.class, RETURNS_MOCKS);
		CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_MOCKS);

		new AdminAuditLogSearch(null, null, " ", null, null, null)
				.toSpecification(ZoneId.of("UTC")).toPredicate(root, mock(CriteriaQuery.class), cb);

		verify(root, never()).get(anyString());
		verify(cb, never()).lessThan(any(Path.class), any(LocalDateTime.class));
	}
}
