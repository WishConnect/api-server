package com.wishconnect.domain.scholarship.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 관리자가 내린 장학금을 수집 배치가 되살리지 않는지.
 *
 * <p>동기화(updateFromApi)·재파싱(applyLlmParsed)은 같은 공고가 다시 들어오면 deletedAt 을 풀고 모집 상태를
 * 날짜로 다시 계산한다. 그 경로가 관리자가 내린 공고를 다음 날 OPEN 으로 되살릴 수 있었다.
 */
@DisplayName("관리자 내리기 — 수집 배치 부활 차단")
class ScholarshipTakedownGuardTest {

	private static final LocalDateTime FUTURE = LocalDateTime.now().plusDays(30);

	private Scholarship scholarship() {
		return Scholarship.builder()
				.title("원래 제목").provider("기관").scholarshipType(ScholarshipType.EXTERNAL)
				.applicationStartAt(LocalDateTime.now().minusDays(1)).applicationEndAt(FUTURE)
				.recruitmentStatus(RecruitmentStatus.OPEN).primarySource("KOSAF").dedupKey("k")
				.build();
	}

	private void sync(Scholarship scholarship) {
		scholarship.updateFromApi("동기화 제목", "기관", null, null, ScholarshipType.EXTERNAL,
				LocalDateTime.now().minusDays(1), FUTURE, RecruitmentStatus.OPEN, null, null, "KOSAF", "k", null);
		scholarship.updateActive(true);
	}

	private void reparse(Scholarship scholarship) {
		scholarship.applyLlmParsed("재파싱 제목", "기관", null, null, ScholarshipType.EXTERNAL,
				LocalDateTime.now().minusDays(1), FUTURE, null, null, null, null, null, null, null, null, false,
				null, null, null, null);
	}

	@Test
	@DisplayName("관리자가 내린 공고는 동기화가 되살리지도, 내용·노출을 바꾸지도 않는다")
	void syncDoesNotReviveAdminDeletion() {
		Scholarship scholarship = scholarship();
		scholarship.deleteByAdmin(UUID.randomUUID(), "선발 결과 안내 공고");

		sync(scholarship);

		assertThat(scholarship.isDeleted()).isTrue();
		assertThat(scholarship.isActive()).isFalse();
		assertThat(scholarship.getTitle()).isEqualTo("원래 제목");
	}

	@Test
	@DisplayName("관리자가 내린 공고는 재파싱도 되살리지 않는다")
	void reparseDoesNotReviveAdminDeletion() {
		Scholarship scholarship = scholarship();
		scholarship.deleteByAdmin(UUID.randomUUID(), "채용 공고");

		reparse(scholarship);

		assertThat(scholarship.isDeleted()).isTrue();
		assertThat(scholarship.getTitle()).isEqualTo("원래 제목");
	}

	@Test
	@DisplayName("병합으로 내린 중복 쪽도 되살리지 않는다")
	void syncDoesNotReviveMergedDuplicate() {
		Scholarship scholarship = scholarship();
		scholarship.markMergedInto(10L, UUID.randomUUID());

		sync(scholarship);

		assertThat(scholarship.isDeleted()).isTrue();
		assertThat(scholarship.getDeleteReason()).isEqualTo("병합: #10 로 합쳐짐");
	}

	@Test
	@DisplayName("수집 배치가 스스로 내린 공고는 기존대로 다시 들어오면 되살린다")
	void syncStillRevivesBatchDeletion() {
		Scholarship scholarship = scholarship();
		scholarship.softDelete();

		sync(scholarship);

		assertThat(scholarship.isDeleted()).isFalse();
		assertThat(scholarship.getTitle()).isEqualTo("동기화 제목");
	}

	@Test
	@DisplayName("복원하면 내린 사람·사유를 지우고, 마감 공고는 마감인 채로 둔다")
	void restoreKeepsClosedStatus() {
		Scholarship scholarship = scholarship();
		scholarship.updateRecruitmentStatusByAdmin(RecruitmentStatus.CLOSED);
		scholarship.deleteByAdmin(UUID.randomUUID(), "오등록");

		scholarship.restoreByAdmin();

		assertThat(scholarship.isDeleted()).isFalse();
		assertThat(scholarship.getDeletedBy()).isNull();
		assertThat(scholarship.getDeleteReason()).isNull();
		assertThat(scholarship.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.CLOSED);
		assertThat(scholarship.isActive()).isFalse();
	}
}
