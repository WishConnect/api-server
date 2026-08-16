package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.wishconnect.domain.scholarship.dto.ScholarshipDetailResponse;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.entity.ScholarshipDocument;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipTimelineRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipTagRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ScholarshipDetailServiceTest {

	private static final UUID USER_ID = UUID.randomUUID();

	@Mock
	private ScholarshipRepository scholarshipRepository;

	@Mock
	private ScholarshipConditionRepository scholarshipConditionRepository;

	@Mock
	private ScholarshipDocumentRepository scholarshipDocumentRepository;

	@Mock
	private ScholarshipTimelineRepository scholarshipTimelineRepository;

	@Mock
	private ScrapRepository scrapRepository;

	@Mock
	private ScholarshipTagRepository scholarshipTagRepository;

	@Mock
	private ScholarshipRecommendationService scholarshipRecommendationService;

	@Mock
	private com.wishconnect.domain.common.repository.ImageRepository imageRepository;

	@Mock
	private com.wishconnect.domain.common.service.ImageStorageService imageStorageService;

	@InjectMocks
	private ScholarshipDetailService scholarshipDetailService;

	private Scholarship scholarship(long id) {
		Scholarship scholarship = Scholarship.builder()
				.title("테스트 장학금")
				.provider("테스트재단")
				.scholarshipType(ScholarshipType.EXTERNAL)
				.amount(5_000_000L)
				.selectionCount(10)
				.applicationStartAt(LocalDateTime.now().minusDays(5))
				.applicationEndAt(LocalDateTime.now().plusDays(5))
				.recruitmentStatus(RecruitmentStatus.OPEN)
				.homepageUrl("https://example.com")
				.build();
		ReflectionTestUtils.setField(scholarship, "id", id);
		return scholarship;
	}

	@Test
	@DisplayName("상세: 요약 테이블(조건 매핑)·모집기간 폴백 일정·서류·스크랩 여부를 반환한다")
	void returnsDetail() {
		Scholarship scholarship = scholarship(1L);
		given(scholarshipRepository.findById(1L)).willReturn(Optional.of(scholarship));
		given(scholarshipConditionRepository.findAllByScholarshipId(1L)).willReturn(List.of(
				ScholarshipCondition.builder()
						.scholarship(scholarship)
						.conditionType(ConditionType.ACADEMIC_CRITERIA)
						.operator(ConditionOperator.GTE)
						.valueString("평점 3.0 이상")
						.autoExtracted(false)
						.build()));
		given(scholarshipDocumentRepository.findAllByScholarshipIdOrderByDisplayOrderAsc(1L)).willReturn(List.of(
				ScholarshipDocument.builder()
						.scholarship(scholarship)
						.name("자기소개서 1부")
						.essay(true)
						.displayOrder(1)
						.build()));
		given(scholarshipTimelineRepository.findAllByScholarshipIdOrderByDisplayOrderAsc(1L)).willReturn(List.of());
		given(scrapRepository.existsByUserIdAndScholarshipId(USER_ID, 1L)).willReturn(true);
		given(imageRepository.findFirstByEntityTypeAndEntityIdOrderByIdAsc(
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()))
				.willReturn(Optional.empty());
		given(scholarshipRecommendationService.getMatchReasons(eq(USER_ID), any(), anyList()))
				.willReturn(List.of("성적 기준 충족(평점 3.50)"));

		ScholarshipDetailResponse detail = scholarshipDetailService.getDetail(USER_ID, 1L);

		assertThat(detail.title()).isEqualTo("테스트 장학금");
		assertThat(detail.isScrapped()).isTrue();
		assertThat(detail.summary().gpaRequirement()).isEqualTo("평점 3.0 이상");
		assertThat(detail.summary().supportAmount()).isEqualTo("최대 500만원");
		assertThat(detail.summary().selectedCount()).isEqualTo("10명");
		assertThat(detail.selectionSchedule()).hasSize(1);
		assertThat(detail.selectionSchedule().get(0).step()).isEqualTo("서류접수");
		assertThat(detail.selectionSchedule().get(0).status()).isEqualTo("CURRENT");
		assertThat(detail.requiredDocuments()).extracting("name").containsExactly("자기소개서 1부");
		assertThat(detail.matchReasons()).isNotEmpty();
	}

	@Test
	@DisplayName("없는 장학금이면 SCHOLARSHIP_NOT_FOUND")
	void notFound() {
		given(scholarshipRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> scholarshipDetailService.getDetail(USER_ID, 99L))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.SCHOLARSHIP_NOT_FOUND);
	}
}
