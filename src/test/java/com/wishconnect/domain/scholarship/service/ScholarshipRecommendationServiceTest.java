package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse;
import com.wishconnect.domain.scholarship.dto.HomeSummaryResponse;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.user.entity.UserProfile;
import com.wishconnect.domain.user.repository.UserProfileRepository;
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
class ScholarshipRecommendationServiceTest {

	private static final UUID USER_ID = UUID.randomUUID();

	@Mock
	private ScholarshipRepository scholarshipRepository;

	@Mock
	private ScholarshipConditionRepository scholarshipConditionRepository;

	@Mock
	private UserProfileRepository userProfileRepository;

	@InjectMocks
	private ScholarshipRecommendationService scholarshipRecommendationService;

	private Scholarship scholarship(long id, String title, LocalDateTime endAt) {
		Scholarship scholarship = Scholarship.builder()
				.title(title)
				.provider("테스트기관")
				.scholarshipType(ScholarshipType.EXTERNAL)
				.applicationEndAt(endAt)
				.recruitmentStatus(RecruitmentStatus.OPEN)
				.build();
		ReflectionTestUtils.setField(scholarship, "id", id);
		return scholarship;
	}

	private ScholarshipCondition incomeCondition(Scholarship scholarship, int maxLevel) {
		return ScholarshipCondition.builder()
				.scholarship(scholarship)
				.conditionType(ConditionType.INCOME_CRITERIA)
				.operator(ConditionOperator.LTE)
				.valueInt(maxLevel)
				.valueString("소득 " + maxLevel + "분위 이하")
				.autoExtracted(false)
				.build();
	}

	@Test
	@DisplayName("불충족 조건이 있는 장학금은 추천 목록에서 제외된다")
	void excludesMismatchedScholarship() {
		Scholarship fits = scholarship(1L, "지원가능 장학금", LocalDateTime.now().plusDays(30));
		Scholarship excluded = scholarship(2L, "분위초과 장학금", LocalDateTime.now().plusDays(30));
		given(userProfileRepository.findByUserId(USER_ID))
				.willReturn(Optional.of(UserProfile.builder().incomeLevel(5).build()));
		given(scholarshipRepository.findAllByRecruitmentStatusAndActiveTrueAndDeletedAtIsNull(RecruitmentStatus.OPEN))
				.willReturn(List.of(fits, excluded));
		given(scholarshipConditionRepository.findAllByScholarshipIn(List.of(fits, excluded)))
				.willReturn(List.of(incomeCondition(fits, 8), incomeCondition(excluded, 3)));

		List<CuratedScholarshipResponse> result = scholarshipRecommendationService.getCuratedScholarships(USER_ID);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).scholarshipId()).isEqualTo(1L);
		assertThat(result.get(0).matchReason()).contains("소득분위 충족");
	}

	@Test
	@DisplayName("조건 충족 장학금이 점수순으로 정렬된다 (마감임박 가점 반영)")
	void sortsByScoreWithDeadlineBoost() {
		Scholarship deadlineSoon = scholarship(1L, "마감임박", LocalDateTime.now().plusDays(3));
		Scholarship normal = scholarship(2L, "여유", LocalDateTime.now().plusDays(60));
		given(userProfileRepository.findByUserId(USER_ID))
				.willReturn(Optional.of(UserProfile.builder().incomeLevel(3).build()));
		given(scholarshipRepository.findAllByRecruitmentStatusAndActiveTrueAndDeletedAtIsNull(RecruitmentStatus.OPEN))
				.willReturn(List.of(normal, deadlineSoon));
		given(scholarshipConditionRepository.findAllByScholarshipIn(List.of(normal, deadlineSoon)))
				.willReturn(List.of(incomeCondition(normal, 8), incomeCondition(deadlineSoon, 8)));

		List<CuratedScholarshipResponse> result = scholarshipRecommendationService.getCuratedScholarships(USER_ID);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).scholarshipId()).isEqualTo(1L);
		assertThat(result.get(0).matchScore()).isGreaterThan(result.get(1).matchScore());
	}

	@Test
	@DisplayName("프로필이 없으면 배제 없이 전체 OPEN 목록을 반환한다 (온보딩 전 폴백)")
	void fallbackWithoutProfile() {
		Scholarship s1 = scholarship(1L, "장학금1", LocalDateTime.now().plusDays(10));
		given(userProfileRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
		given(scholarshipRepository.findAllByRecruitmentStatusAndActiveTrueAndDeletedAtIsNull(RecruitmentStatus.OPEN))
				.willReturn(List.of(s1));
		given(scholarshipConditionRepository.findAllByScholarshipIn(List.of(s1)))
				.willReturn(List.of(incomeCondition(s1, 3)));

		List<CuratedScholarshipResponse> result = scholarshipRecommendationService.getCuratedScholarships(USER_ID);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).matchReason()).contains("프로필 등록 전");
	}

	@Test
	@DisplayName("홈 요약: 맞춤 건수와 D-7 이내 마감임박 건수를 센다")
	void homeSummaryCounts() {
		Scholarship fits = scholarship(1L, "지원가능", LocalDateTime.now().plusDays(3));
		Scholarship excluded = scholarship(2L, "분위초과", LocalDateTime.now().plusDays(30));
		given(userProfileRepository.findByUserId(USER_ID))
				.willReturn(Optional.of(UserProfile.builder().incomeLevel(5).build()));
		given(scholarshipRepository.findAllByRecruitmentStatusAndActiveTrueAndDeletedAtIsNull(RecruitmentStatus.OPEN))
				.willReturn(List.of(fits, excluded));
		given(scholarshipConditionRepository.findAllByScholarshipIn(List.of(fits, excluded)))
				.willReturn(List.of(incomeCondition(fits, 8), incomeCondition(excluded, 3)));

		HomeSummaryResponse summary = scholarshipRecommendationService.getHomeSummary(USER_ID);

		assertThat(summary.matchedCount()).isEqualTo(1);
		assertThat(summary.deadlineSoonCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("OPEN 장학금이 없으면 빈 결과")
	void emptyWhenNoOpenScholarships() {
		given(userProfileRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
		given(scholarshipRepository.findAllByRecruitmentStatusAndActiveTrueAndDeletedAtIsNull(RecruitmentStatus.OPEN))
				.willReturn(List.of());

		assertThat(scholarshipRecommendationService.getCuratedScholarships(USER_ID)).isEmpty();
		assertThat(scholarshipRecommendationService.getHomeSummary(USER_ID))
				.isEqualTo(new HomeSummaryResponse(0, 0));
	}
}
