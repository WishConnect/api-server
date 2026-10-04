package com.wishconnect.domain.scholarship.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 관리자 수정은 모집 상태를 날짜로 다시 계산하지 않는다. 상태는 사람이 고른다. */
@DisplayName("관리자 수정 — 모집 상태 자동 계산 금지")
class ScholarshipAdminStatusTest {

	private Scholarship closedEarly() {
		Scholarship scholarship = Scholarship.builder()
				.title("조기 마감된 장학금").scholarshipType(ScholarshipType.EXTERNAL)
				.applicationStartAt(LocalDateTime.now().minusDays(5))
				.applicationEndAt(LocalDateTime.now().plusDays(10))
				.recruitmentStatus(RecruitmentStatus.OPEN).dedupKey("k").build();
		scholarship.updateRecruitmentStatusByAdmin(RecruitmentStatus.CLOSED);
		return scholarship;
	}

	@Test
	@DisplayName("제목만 고쳐도 조기 마감(CLOSED)이 OPEN 으로 되돌아가지 않는다")
	void partialUpdateKeepsStatus() {
		Scholarship scholarship = closedEarly();

		scholarship.updateByAdmin("제목 수정", null, null, null, null, null, null, null, null, null);

		assertThat(scholarship.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.CLOSED);
		assertThat(scholarship.getTitle()).isEqualTo("제목 수정");
	}

	@Test
	@DisplayName("통합 수정에서 상태를 비우면 날짜로 계산하지 않고 기존 상태를 둔다")
	void aggregateWithoutStatusKeepsStatus() {
		Scholarship scholarship = closedEarly();

		scholarship.replaceByAdmin("제목", null, null, null, ScholarshipType.EXTERNAL,
				LocalDateTime.now().minusDays(5), LocalDateTime.now().plusDays(10), null, null, null, null, null,
				null, false, null, null, null, null, null, null, null, null);

		assertThat(scholarship.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.CLOSED);
		assertThat(scholarship.isActive()).isFalse();
	}

	@Test
	@DisplayName("통합 수정에서 고른 상태는 날짜와 모순이어도 그대로 저장한다(경고는 statusCheck 로)")
	void aggregateKeepsChosenStatus() {
		Scholarship scholarship = closedEarly();

		scholarship.replaceByAdmin("제목", null, null, null, ScholarshipType.EXTERNAL,
				LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(1), RecruitmentStatus.OPEN,
				null, null, null, null, null, false, null, null, null, null, null, null, null, null);

		assertThat(scholarship.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.OPEN);
	}
}
