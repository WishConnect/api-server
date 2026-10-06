package com.wishconnect.domain.scholarship.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wishconnect.domain.scholarship.config.ScholarshipApiProperties;
import com.wishconnect.domain.scholarship.util.ScholarshipMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 모집기간 수기 고정(period_locked) — 동기화·재파싱이 관리자가 고친 기간만 건너뛰는지.
 */
@DisplayName("모집기간 수기 고정 — 자동 수집 경로")
class ScholarshipPeriodLockTest {

	private static final LocalDateTime ADMIN_START = LocalDateTime.now().minusDays(3).withNano(0);
	private static final LocalDateTime ADMIN_END = LocalDateTime.now().plusDays(10).withNano(0);
	private static final LocalDateTime FEED_START = LocalDateTime.now().minusDays(40).withNano(0);
	private static final LocalDateTime FEED_END = LocalDateTime.now().minusDays(20).withNano(0);

	private Scholarship scholarship(boolean locked) {
		Scholarship scholarship = Scholarship.builder()
				.title("원래 제목").provider("기관").scholarshipType(ScholarshipType.EXTERNAL)
				.applicationStartAt(ADMIN_START).applicationEndAt(ADMIN_END)
				.recruitmentStatus(RecruitmentStatus.OPEN).primarySource("KOSAF").dedupKey("k")
				.build();
		scholarship.changePeriodLocked(locked);
		return scholarship;
	}

	private void sync(Scholarship scholarship) {
		scholarship.updateFromApi("동기화 제목", "기관", null, null, ScholarshipType.EXTERNAL,
				FEED_START, FEED_END, RecruitmentStatus.CLOSED, 5, 100L, "KOSAF", "k", null);
	}

	private void reparse(Scholarship scholarship) {
		scholarship.applyLlmParsed("재파싱 제목", "기관", null, null, ScholarshipType.EXTERNAL,
				FEED_START, FEED_END, 7, 200L, null, null, null, null, null, null, false,
				null, null, null, null);
	}

	@Test
	@DisplayName("잠긴 기간은 동기화가 덮지 않고, 나머지 필드는 갱신한다")
	void syncSkipsLockedPeriod() {
		Scholarship scholarship = scholarship(true);

		sync(scholarship);

		assertThat(scholarship.getApplicationStartAt()).isEqualTo(ADMIN_START);
		assertThat(scholarship.getApplicationEndAt()).isEqualTo(ADMIN_END);
		assertThat(scholarship.getTitle()).isEqualTo("동기화 제목");
		assertThat(scholarship.getSelectionCount()).isEqualTo(5);
		assertThat(scholarship.isPeriodLocked()).isTrue();
	}

	@Test
	@DisplayName("잠기지 않았으면 동기화가 기간을 기존대로 덮는다")
	void syncOverwritesUnlockedPeriod() {
		Scholarship scholarship = scholarship(false);

		sync(scholarship);

		assertThat(scholarship.getApplicationStartAt()).isEqualTo(FEED_START);
		assertThat(scholarship.getApplicationEndAt()).isEqualTo(FEED_END);
	}

	@Test
	@DisplayName("잠긴 기간은 재파싱이 덮지 않고, 모집 상태는 남은(잠긴) 기간으로 계산한다")
	void reparseSkipsLockedPeriod() {
		Scholarship scholarship = scholarship(true);

		reparse(scholarship);

		assertThat(scholarship.getApplicationStartAt()).isEqualTo(ADMIN_START);
		assertThat(scholarship.getApplicationEndAt()).isEqualTo(ADMIN_END);
		assertThat(scholarship.getTitle()).isEqualTo("재파싱 제목");
		assertThat(scholarship.getAmount()).isEqualTo(200L);
		// 파싱한 기간(이미 지남)으로 계산했다면 CLOSED·비노출이 됐을 것이다.
		assertThat(scholarship.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.OPEN);
		assertThat(scholarship.isActive()).isTrue();
	}

	@Test
	@DisplayName("잠기지 않았으면 재파싱이 기간과 상태를 기존대로 바꾼다")
	void reparseOverwritesUnlockedPeriod() {
		Scholarship scholarship = scholarship(false);

		reparse(scholarship);

		assertThat(scholarship.getApplicationEndAt()).isEqualTo(FEED_END);
		assertThat(scholarship.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.CLOSED);
	}

	@Test
	@DisplayName("동기화 매퍼는 잠긴 장학금의 모집 상태·노출을 응답 날짜가 아니라 잠긴 기간으로 정한다")
	void mapperUsesLockedPeriodForStatus() {
		Scholarship scholarship = scholarship(true);
		ObjectNode item = new ObjectMapper().createObjectNode()
				.put("상품명", "동기화 제목")
				.put("운영기관명", "기관")
				.put("모집시작일", FEED_START.toLocalDate().toString())
				.put("모집종료일", FEED_END.toLocalDate().toString());
		ScholarshipApiProperties properties = new ScholarshipApiProperties(
				null, null, null, null, null, null, null, null, null);

		new ScholarshipMapper().toScholarship(item, scholarship, properties);

		assertThat(scholarship.getApplicationEndAt()).isEqualTo(ADMIN_END);
		assertThat(scholarship.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.OPEN);
		assertThat(scholarship.isActive()).isTrue();
		assertThat(scholarship.getTitle()).isEqualTo("동기화 제목");
	}

	@Test
	@DisplayName("동기화 매퍼는 잠기지 않은 장학금에는 응답 날짜·상태를 그대로 쓴다")
	void mapperOverwritesUnlocked() {
		Scholarship scholarship = scholarship(false);
		ObjectNode item = new ObjectMapper().createObjectNode()
				.put("상품명", "동기화 제목")
				.put("모집시작일", FEED_START.toLocalDate().toString())
				.put("모집종료일", FEED_END.toLocalDate().toString());

		new ScholarshipMapper().toScholarship(item, scholarship,
				new ScholarshipApiProperties(null, null, null, null, null, null, null, null, null));

		assertThat(scholarship.getApplicationEndAt()).isEqualTo(LocalDate.parse(FEED_END.toLocalDate().toString())
				.atStartOfDay());
		assertThat(scholarship.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.CLOSED);
		assertThat(scholarship.isActive()).isFalse();
	}
}
