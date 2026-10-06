package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipDetailResponse;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipEditSnapshot;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.service.ScholarshipChangeFields.Kind;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("감사 로그 필드 선택 복구 — 선발 일정·모집기간 고정")
class ScholarshipFieldRestorerTest {

	private static final Long ID = 900L;

	@Mock private ScholarshipRepository scholarshipRepository;
	@Mock private ScholarshipManualService scholarshipManualService;
	@Mock private ScholarshipAdminOverviewService scholarshipAdminOverviewService;
	@Mock private ScholarshipManualAggregateStore aggregateStore;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
	private ScholarshipFieldRestorer restorer;
	private Scholarship scholarship;

	private static final AdminScholarshipDetailResponse.TimelineData CURRENT_ROW =
			new AdminScholarshipDetailResponse.TimelineData(5L, "INTERVIEW", "면접", "SINGLE",
					LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 20), null, null, null, "MANUAL", 0);
	private static final AdminScholarshipDetailResponse.TimelineData RECORDED_ROW =
			new AdminScholarshipDetailResponse.TimelineData(3L, "DOC_RESULT", "서류 발표", "TBD",
					null, null, "11월 중", null, "원문", "MANUAL", 0);

	@BeforeEach
	void setUp() {
		restorer = new ScholarshipFieldRestorer(scholarshipRepository, scholarshipManualService,
				scholarshipAdminOverviewService, aggregateStore, objectMapper);
		scholarship = Scholarship.builder().title("현재 제목").scholarshipType(ScholarshipType.EXTERNAL)
				.recruitmentStatus(RecruitmentStatus.OPEN).build();
		scholarship.changePeriodLocked(true);
		given(scholarshipRepository.findById(ID)).willReturn(Optional.of(scholarship));
		given(scholarshipAdminOverviewService.detail(ID)).willReturn(detail("현재 제목", true, List.of(CURRENT_ROW)));
	}

	/** 2026-10 이전 통합 수정 기록: timeline 키와 periodLocked 가 없다. */
	private JsonNode oldSnapshot() {
		ObjectNode node = objectMapper.valueToTree(
				AdminScholarshipEditSnapshot.from(detail("옛 제목", false, List.of())));
		node.remove("timeline");
		((ObjectNode) node.get("scholarship")).remove("periodLocked");
		return node;
	}

	@Test
	@DisplayName("일정 키가 없는 옛 스냅샷으로는 일정을 고르더라도 현재 일정을 비우지 않는다")
	void keepsTimelineForOldSnapshot() {
		restorer.restore(Kind.AGGREGATE, ID, oldSnapshot(), Set.of("timeline", "conditions"));

		verify(aggregateStore, never()).replaceTimelineFromSnapshot(anyLong(), any());
		verify(aggregateStore).replaceConditionsFromSnapshot(eq(ID), any());
	}

	@Test
	@DisplayName("고정 키가 없는 옛 스냅샷으로는 모집기간 고정을 바꾸지 않는다")
	void keepsPeriodLockForOldSnapshot() {
		restorer.restore(Kind.AGGREGATE, ID, oldSnapshot(), Set.of("periodLocked", "title"));

		assertThat(scholarship.isPeriodLocked()).isTrue();
		assertThat(scholarship.getTitle()).isEqualTo("옛 제목");
	}

	@Test
	@DisplayName("새 스냅샷에서 일정을 고르면 기록된 일정으로 바꾼다")
	void restoresTimelineFromNewSnapshot() {
		JsonNode recorded = objectMapper.valueToTree(
				AdminScholarshipEditSnapshot.from(detail("옛 제목", false, List.of(RECORDED_ROW))));

		restorer.restore(Kind.AGGREGATE, ID, recorded, Set.of("timeline"));

		verify(aggregateStore).replaceTimelineFromSnapshot(ID, List.of(RECORDED_ROW));
		assertThat(scholarship.getTitle()).isEqualTo("현재 제목");
		assertThat(scholarship.isPeriodLocked()).isTrue();
	}

	@Test
	@DisplayName("새 스냅샷에서 고정을 고르면 기록 값으로 바꾸고, 고르지 않으면 그대로 둔다")
	void restoresPeriodLockOnlyWhenChosen() {
		JsonNode recorded = objectMapper.valueToTree(
				AdminScholarshipEditSnapshot.from(detail("옛 제목", false, List.of(RECORDED_ROW))));

		restorer.restore(Kind.AGGREGATE, ID, recorded, Set.of("title"));
		assertThat(scholarship.isPeriodLocked()).isTrue();
		verify(aggregateStore, never()).replaceTimelineFromSnapshot(anyLong(), any());

		restorer.restore(Kind.AGGREGATE, ID, recorded, Set.of("periodLocked"));
		assertThat(scholarship.isPeriodLocked()).isFalse();
	}

	@Test
	@DisplayName("현재 스냅샷에 일정과 고정 여부가 들어간다")
	void currentSnapshotHasTimeline() {
		JsonNode current = restorer.currentSnapshot(Kind.AGGREGATE, ID);

		assertThat(current.get("timeline")).hasSize(1);
		assertThat(current.path("scholarship").path("periodLocked").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("변경 요약기가 실제 스냅샷 객체에서 일정 건수를 센다")
	void summarizerCountsTimelineRows() {
		ScholarshipChangeSummarizer summarizer = new ScholarshipChangeSummarizer(objectMapper);
		AdminScholarshipEditSnapshot before = AdminScholarshipEditSnapshot.from(
				detail("제목", false, List.of(CURRENT_ROW)));
		AdminScholarshipEditSnapshot after = AdminScholarshipEditSnapshot.from(
				detail("제목", true, List.of(CURRENT_ROW, RECORDED_ROW)));

		assertThat(summarizer.summarize(before, after))
				.isEqualTo("변경: 모집기간 수기 고정(false→true), 선발 일정 1건 변경");
	}

	static AdminScholarshipDetailResponse detail(String title, boolean periodLocked,
			List<AdminScholarshipDetailResponse.TimelineData> timeline) {
		AdminScholarshipDetailResponse.ScholarshipData data = new AdminScholarshipDetailResponse.ScholarshipData(
				ID, title, "기관", null, null, "EXTERNAL", "OPEN",
				LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 30, 23, 59), null, null,
				true, true, "MANUAL", null, null, null, false, null, null, null, null, null, null, null, null,
				LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 0), null, periodLocked);
		return new AdminScholarshipDetailResponse(data, List.of(), List.of(), List.of(), timeline, List.of(), null);
	}
}
