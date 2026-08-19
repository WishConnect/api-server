package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualFullRequest;
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
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.entity.SubmissionChannel;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
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
	@Mock private ConditionRefResolver conditionRefResolver;

	private ScholarshipManualAggregateStore store;

	@BeforeEach
	void setUp() {
		store = new ScholarshipManualAggregateStore(
				scholarshipRepository, rawScholarshipRepository, scholarshipConditionRepository,
				scholarshipDocumentRepository, conditionRefResolver, new ObjectMapper().findAndRegisterModules());
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
