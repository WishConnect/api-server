package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualFullRequest;
import com.wishconnect.domain.scholarship.dto.TimelineItemRequest;
import com.wishconnect.domain.scholarship.entity.ConditionNecessity;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionRef;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.entity.ScholarshipDocument;
import com.wishconnect.domain.scholarship.entity.ScholarshipTimeline;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.entity.SubmissionChannel;
import com.wishconnect.domain.scholarship.entity.TimelineDateType;
import com.wishconnect.domain.scholarship.entity.TimelineOrigin;
import com.wishconnect.domain.scholarship.entity.TimelineStageCode;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipTimelineRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("장학금 통합 수기 등록 저장")
class ScholarshipManualAggregateStoreTest {

	@Mock private ScholarshipRepository scholarshipRepository;
	@Mock private RawScholarshipRepository rawScholarshipRepository;
	@Mock private ScholarshipConditionRepository scholarshipConditionRepository;
	@Mock private ScholarshipDocumentRepository scholarshipDocumentRepository;
	@Mock private ScholarshipTimelineRepository scholarshipTimelineRepository;
	@Mock private ConditionRefResolver conditionRefResolver;

	private ScholarshipManualAggregateStore store;

	@BeforeEach
	void setUp() {
		store = new ScholarshipManualAggregateStore(
				scholarshipRepository, rawScholarshipRepository, scholarshipConditionRepository,
				scholarshipDocumentRepository, scholarshipTimelineRepository, conditionRefResolver,
				new ObjectMapper().findAndRegisterModules());
	}

	@Test
	@DisplayName("장학금·원문·조건참조·서류를 한 묶음으로 저장한다")
	void savesAggregate() {
		ScholarshipManualFullRequest request = request(
				LocalDateTime.of(2026, 8, 19, 0, 0), LocalDateTime.of(2026, 9, 4, 23, 59));
		given(scholarshipRepository.save(any())).willAnswer(invocation -> {
			Scholarship value = invocation.getArgument(0);
			ReflectionTestUtils.setField(value, "id", 101L);
			return value;
		});
		given(rawScholarshipRepository.save(any())).willAnswer(invocation -> {
			RawScholarship value = invocation.getArgument(0);
			ReflectionTestUtils.setField(value, "id", 201L);
			return value;
		});
		given(conditionRefResolver.resolve(ConditionType.REGION_RESIDENCY, List.of("서울 광진구")))
				.willReturn(Set.of(ConditionRef.ofId(17L)));

		ScholarshipManualAggregateStore.SavedAggregate saved = store.create(request);

		assertThat(saved.scholarshipId()).isEqualTo(101L);
		assertThat(saved.rawScholarshipId()).isEqualTo(201L);
		assertThat(saved.conditionCount()).isEqualTo(1);
		assertThat(saved.conditionRefCount()).isEqualTo(1);
		assertThat(saved.documentCount()).isEqualTo(1);

		ArgumentCaptor<RawScholarship> rawCaptor = ArgumentCaptor.forClass(RawScholarship.class);
		verify(rawScholarshipRepository).save(rawCaptor.capture());
		assertThat(rawCaptor.getValue().getSource()).isEqualTo("MANUAL");
		assertThat(rawCaptor.getValue().getParseStatus()).isEqualTo(ParseStatus.PARSED);
		assertThat(rawCaptor.getValue().getRawJson()).containsEntry("title", "건국희망 장학");

		ArgumentCaptor<ScholarshipCondition> conditionCaptor = ArgumentCaptor.forClass(ScholarshipCondition.class);
		verify(scholarshipConditionRepository).save(conditionCaptor.capture());
		assertThat(conditionCaptor.getValue().getRefs()).containsExactly(ConditionRef.ofId(17L));
		assertThat(conditionCaptor.getValue().isAutoExtracted()).isTrue();

		ArgumentCaptor<ScholarshipDocument> documentCaptor = ArgumentCaptor.forClass(ScholarshipDocument.class);
		verify(scholarshipDocumentRepository).save(documentCaptor.capture());
		assertThat(documentCaptor.getValue().getDownloadUrl()).isEqualTo("https://example.com/form.pdf");
	}

	@Test
	@DisplayName("종료일이 시작일보다 빠르면 아무것도 저장하지 않는다")
	void rejectsInvalidPeriod() {
		ScholarshipManualFullRequest request = request(
				LocalDateTime.of(2026, 9, 4, 0, 0), LocalDateTime.of(2026, 8, 19, 0, 0));

		assertThatThrownBy(() -> store.create(request))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.INVALID_APPLICATION_PERIOD);
		verify(scholarshipRepository, never()).save(any());
	}

	@Test
	@DisplayName("실패 원본을 수기 정제하면 새 장학금에 연결하고 PARSED로 바꾼다")
	void createsScholarshipFromFailedRaw() {
		RawScholarship raw = RawScholarship.builder()
				.source("UNIV_KONKUK")
				.sourceId("notice-77")
				.sourceUrl("https://example.com/77")
				.parseStatus(ParseStatus.FAILED)
				.parseError("본문 파싱 실패")
				.build();
		ReflectionTestUtils.setField(raw, "id", 77L);
		given(rawScholarshipRepository.findById(77L)).willReturn(Optional.of(raw));
		given(scholarshipRepository.save(any())).willAnswer(invocation -> {
			Scholarship value = invocation.getArgument(0);
			ReflectionTestUtils.setField(value, "id", 177L);
			return value;
		});

		ScholarshipManualAggregateStore.SavedAggregate saved = store.createFromRaw(
				77L, request(LocalDateTime.of(2026, 8, 19, 0, 0),
						LocalDateTime.of(2026, 9, 4, 23, 59)));

		assertThat(saved.scholarshipId()).isEqualTo(177L);
		assertThat(saved.rawScholarshipId()).isEqualTo(77L);
		assertThat(raw.getParseStatus()).isEqualTo(ParseStatus.PARSED);
		assertThat(raw.getScholarship().getId()).isEqualTo(177L);
	}

	@Test
	@DisplayName("통합 수정은 기본정보를 바꾸고 조건·서류를 최종 목록으로 교체한다")
	void replacesAggregateForManualEdit() {
		Scholarship existing = Scholarship.builder()
				.title("수정 전")
				.scholarshipType(ScholarshipType.EXTERNAL)
				.recruitmentStatus(RecruitmentStatus.OPEN)
				.build();
		ReflectionTestUtils.setField(existing, "id", 301L);
		given(scholarshipRepository.findById(301L)).willReturn(Optional.of(existing));
		given(conditionRefResolver.resolve(ConditionType.REGION_RESIDENCY, List.of("서울 광진구")))
				.willReturn(Set.of(ConditionRef.ofId(17L)));

		ScholarshipManualAggregateStore.SavedAggregate saved = store.update(
				301L, request(LocalDateTime.of(2026, 8, 19, 0, 0),
						LocalDateTime.of(2026, 9, 4, 23, 59)));

		assertThat(saved.scholarshipId()).isEqualTo(301L);
		assertThat(existing.getTitle()).isEqualTo("건국희망 장학");
		assertThat(existing.isVerified()).isTrue();
		verify(scholarshipConditionRepository).deleteByScholarship(existing);
		verify(scholarshipDocumentRepository).deleteByScholarship(existing);
		verify(scholarshipConditionRepository).flush();
		verify(scholarshipDocumentRepository).flush();
		verify(scholarshipConditionRepository).save(any(ScholarshipCondition.class));
		verify(scholarshipDocumentRepository).save(any(ScholarshipDocument.class));
	}

	@Test
	@DisplayName("통합 수정에서 timeline 이 null 이면 기존 일정을 지우지도 새로 쓰지도 않는다(조건·서류와 다름)")
	void keepsTimelineWhenNull() {
		Scholarship existing = existing(302L);

		store.update(302L, request(LocalDateTime.of(2026, 8, 19, 0, 0), LocalDateTime.of(2026, 9, 4, 23, 59)));

		verify(scholarshipTimelineRepository, never()).deleteByScholarship(any());
		verify(scholarshipTimelineRepository, never()).save(any());
		// 조건·서류는 null 이어도 교체 규칙 그대로
		verify(scholarshipConditionRepository).deleteByScholarship(existing);
	}

	@Test
	@DisplayName("통합 수정에서 timeline 이 [] 이면 일정을 모두 지운다")
	void deletesTimelineWhenEmpty() {
		Scholarship existing = existing(303L);

		store.update(303L, withTimeline(List.of(), null));

		verify(scholarshipTimelineRepository).deleteByScholarship(existing);
		verify(scholarshipTimelineRepository, never()).save(any());
	}

	@Test
	@DisplayName("통합 수정에서 timeline 목록은 지운 뒤 배열 순서대로 MANUAL 로 다시 쓴다")
	void replacesTimelineWithList() {
		Scholarship existing = existing(304L);

		store.update(304L, withTimeline(List.of(
				new TimelineItemRequest("INTERVIEW", null, "SINGLE", LocalDate.of(2026, 11, 20), null,
						null, "18:00", null),
				new TimelineItemRequest("CUSTOM", "추천서 마감", "RANGE", LocalDate.of(2026, 11, 1),
						LocalDate.of(2026, 11, 5), "무시되는 문구", null, "원문 근거"),
				new TimelineItemRequest("FINAL_RESULT", " ", "TBD", null, null, "12월 중 예정", null, null)),
				null));

		org.mockito.InOrder order = org.mockito.Mockito.inOrder(scholarshipTimelineRepository);
		order.verify(scholarshipTimelineRepository).deleteByScholarship(existing);
		order.verify(scholarshipTimelineRepository).flush();
		ArgumentCaptor<ScholarshipTimeline> captor = ArgumentCaptor.forClass(ScholarshipTimeline.class);
		order.verify(scholarshipTimelineRepository, org.mockito.Mockito.times(3)).save(captor.capture());
		List<ScholarshipTimeline> saved = captor.getAllValues();
		assertThat(saved).extracting(ScholarshipTimeline::getDisplayOrder).containsExactly(0, 1, 2);
		assertThat(saved).extracting(ScholarshipTimeline::getOrigin).containsOnly(TimelineOrigin.MANUAL);
		assertThat(saved).extracting(ScholarshipTimeline::getScholarship).containsOnly(existing);
		assertThat(saved.get(0).getTitle()).isEqualTo("면접");
		assertThat(saved.get(0).getEndDate()).isEqualTo(LocalDate.of(2026, 11, 20));
		assertThat(saved.get(1).getStageCode()).isEqualTo(TimelineStageCode.CUSTOM);
		assertThat(saved.get(1).getDateText()).isNull();
		assertThat(saved.get(2).getTitle()).isEqualTo("최종 발표");
		assertThat(saved.get(2).getDateType()).isEqualTo(TimelineDateType.TBD);
		assertThat(saved.get(2).getDateText()).isEqualTo("12월 중 예정");
	}

	@Test
	@DisplayName("일정 검증에 실패하면 장학금을 조회·수정하기 전에 막는다")
	void rejectsInvalidTimelineBeforeAnyWrite() {
		ScholarshipManualFullRequest request = withTimeline(List.of(
				new TimelineItemRequest("CUSTOM", null, "SINGLE", LocalDate.of(2026, 11, 20), null, null, null, null)),
				null);

		assertThatThrownBy(() -> store.update(305L, request))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.TIMELINE_TITLE_REQUIRED);
		verify(scholarshipRepository, never()).findById(any());
		verify(scholarshipConditionRepository, never()).deleteByScholarship(any());
		verify(scholarshipTimelineRepository, never()).deleteByScholarship(any());
	}

	@Test
	@DisplayName("수기 등록은 timeline 목록을 함께 저장하고, null 이면 아무 일정도 만들지 않는다")
	void createSavesTimelineOnlyWhenGiven() {
		given(scholarshipRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(rawScholarshipRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

		store.create(request(LocalDateTime.of(2026, 8, 19, 0, 0), LocalDateTime.of(2026, 9, 4, 23, 59)));
		verify(scholarshipTimelineRepository, never()).save(any());

		store.create(withTimeline(List.of(
				new TimelineItemRequest("DOC_RESULT", "", "SINGLE", LocalDate.of(2026, 10, 1), null, null, null, null)),
				null));
		ArgumentCaptor<ScholarshipTimeline> captor = ArgumentCaptor.forClass(ScholarshipTimeline.class);
		verify(scholarshipTimelineRepository).save(captor.capture());
		assertThat(captor.getValue().getTitle()).isEqualTo("서류 발표");
		verify(scholarshipTimelineRepository, never()).deleteByScholarship(any());
	}

	@Test
	@DisplayName("통합 수정에서 모집 시작·마감이 현재 값과 달라지면 기간을 수기 고정한다")
	void locksPeriodWhenChanged() {
		Scholarship existing = existing(306L);
		ReflectionTestUtils.setField(existing, "applicationStartAt", LocalDateTime.of(2026, 8, 19, 0, 0));
		ReflectionTestUtils.setField(existing, "applicationEndAt", LocalDateTime.of(2026, 9, 4, 0, 0));

		store.update(306L, request(LocalDateTime.of(2026, 8, 19, 0, 0), LocalDateTime.of(2026, 9, 4, 23, 59)));

		assertThat(existing.isPeriodLocked()).isTrue();
	}

	@Test
	@DisplayName("기간이 그대로면(분 단위 비교, 초 이하는 무시) 고정하지 않는다")
	void keepsUnlockedWhenPeriodSame() {
		Scholarship existing = existing(307L);
		ReflectionTestUtils.setField(existing, "applicationStartAt", LocalDateTime.of(2026, 8, 19, 0, 0, 30));
		ReflectionTestUtils.setField(existing, "applicationEndAt", LocalDateTime.of(2026, 9, 4, 23, 59, 59));

		store.update(307L, request(LocalDateTime.of(2026, 8, 19, 0, 0), LocalDateTime.of(2026, 9, 4, 23, 59)));

		assertThat(existing.isPeriodLocked()).isFalse();
	}

	@Test
	@DisplayName("null → 날짜 생김도 기간 변경으로 본다")
	void locksWhenPeriodAdded() {
		Scholarship existing = existing(308L);

		store.update(308L, request(null, LocalDateTime.of(2026, 9, 4, 23, 59)));

		assertThat(existing.isPeriodLocked()).isTrue();
	}

	@Test
	@DisplayName("periodLocked=false 를 명시하면 기간이 바뀌어도 고정을 푼다(자동 수집 값으로 되돌리기)")
	void explicitFalseUnlocks() {
		Scholarship existing = existing(309L);
		existing.changePeriodLocked(true);

		store.update(309L, withTimeline(request(null, LocalDateTime.of(2026, 12, 1, 0, 0)), null, false));

		assertThat(existing.isPeriodLocked()).isFalse();
	}

	@Test
	@DisplayName("periodLocked=true 를 명시하면 기간이 그대로여도 고정한다")
	void explicitTrueLocks() {
		Scholarship existing = existing(310L);
		ReflectionTestUtils.setField(existing, "applicationStartAt", LocalDateTime.of(2026, 8, 19, 0, 0));
		ReflectionTestUtils.setField(existing, "applicationEndAt", LocalDateTime.of(2026, 9, 4, 23, 59));

		store.update(310L, withTimeline(List.of(), true));

		assertThat(existing.isPeriodLocked()).isTrue();
	}

	@Test
	@DisplayName("이미 고정된 장학금은 기간을 안 바꾸고 periodLocked 를 생략해도 고정을 유지한다")
	void keepsExistingLock() {
		Scholarship existing = existing(311L);
		ReflectionTestUtils.setField(existing, "applicationStartAt", LocalDateTime.of(2026, 8, 19, 0, 0));
		ReflectionTestUtils.setField(existing, "applicationEndAt", LocalDateTime.of(2026, 9, 4, 23, 59));
		existing.changePeriodLocked(true);

		store.update(311L, request(LocalDateTime.of(2026, 8, 19, 0, 0), LocalDateTime.of(2026, 9, 4, 23, 59)));

		assertThat(existing.isPeriodLocked()).isTrue();
	}

	@Test
	@DisplayName("수기 등록·원본 수기 정제는 periodLocked 를 보내도 기본값(false)을 유지한다")
	void createIgnoresPeriodLocked() {
		ArgumentCaptor<Scholarship> captor = ArgumentCaptor.forClass(Scholarship.class);
		given(scholarshipRepository.save(captor.capture())).willAnswer(invocation -> invocation.getArgument(0));
		given(rawScholarshipRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
		RawScholarship raw = RawScholarship.builder().source("UNIV_KONKUK").sourceId("n-1")
				.parseStatus(ParseStatus.FAILED).build();
		given(rawScholarshipRepository.findById(78L)).willReturn(Optional.of(raw));

		store.create(withTimeline(null, true));
		store.createFromRaw(78L, withTimeline(null, true));

		assertThat(captor.getAllValues()).hasSize(2).noneMatch(Scholarship::isPeriodLocked);
	}

	private Scholarship existing(Long id) {
		Scholarship existing = Scholarship.builder()
				.title("수정 전")
				.scholarshipType(ScholarshipType.EXTERNAL)
				.recruitmentStatus(RecruitmentStatus.OPEN)
				.build();
		ReflectionTestUtils.setField(existing, "id", id);
		org.mockito.Mockito.lenient().when(scholarshipRepository.findById(id)).thenReturn(Optional.of(existing));
		return existing;
	}

	static ScholarshipManualFullRequest withTimeline(ScholarshipManualFullRequest b,
			List<TimelineItemRequest> timeline, Boolean periodLocked) {
		return new ScholarshipManualFullRequest(b.title(), b.provider(), b.summary(), b.description(),
				b.scholarshipType(), b.applicationStartAt(), b.applicationEndAt(), b.recruitmentStatus(),
				b.selectionCount(), b.amount(), b.homepageUrl(), b.detailUrl(), b.noticeKind(), b.combined(),
				b.submissionMethod(), b.submissionChannel(), b.submissionEvidence(), b.contact(),
				b.essayRequirement(), b.essayEvidence(), b.interviewRequirement(), b.interviewEvidence(),
				b.source(), b.conditions(), b.documents(), b.imageSourceUrl(), timeline, periodLocked);
	}

	private ScholarshipManualFullRequest withTimeline(List<TimelineItemRequest> timeline, Boolean periodLocked) {
		return withTimeline(request(LocalDateTime.of(2026, 8, 19, 0, 0), LocalDateTime.of(2026, 9, 4, 23, 59)),
				timeline, periodLocked);
	}

	private ScholarshipManualFullRequest request(LocalDateTime start, LocalDateTime end) {
		return new ScholarshipManualFullRequest(
				"건국희망 장학", "건국대학교", "저소득층 등록금 지원", "상세 설명",
				ScholarshipType.INTERNAL, start, end, RecruitmentStatus.OPEN, null, null,
				"https://www.konkuk.ac.kr", "https://www.konkuk.ac.kr/detail", null, false,
				"온라인 신청", SubmissionChannel.ONLINE, "포털에서 신청", "02-450-0000",
				RequirementLevel.REQUIRED, "자기소개서 제출", RequirementLevel.NOT_REQUIRED, "면접 없음",
				new ScholarshipManualFullRequest.Source("https://www.konkuk.ac.kr/detail", "<p>원문</p>"),
				List.of(new ScholarshipManualFullRequest.Condition(
						ConditionType.REGION_RESIDENCY, ConditionOperator.IN, ConditionNecessity.REQUIRED,
						null, null, "서울특별시 거주자", List.of("서울 광진구"))),
				List.of(new ScholarshipManualFullRequest.Document("신청서", false, 0,
						"https://example.com/form.pdf")),
				"https://example.com/poster.jpg");
	}
}
