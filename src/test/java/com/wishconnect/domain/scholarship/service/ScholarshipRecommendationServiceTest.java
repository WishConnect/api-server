package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse;
import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse.ScholarshipCard;
import com.wishconnect.domain.scholarship.dto.HomeSummaryResponse;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
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

	@Mock
	private ScrapRepository scrapRepository;

	@InjectMocks
	private ScholarshipRecommendationService scholarshipRecommendationService;

	private Scholarship scholarship(long id, String title, ScholarshipType type, LocalDateTime endAt,
			LocalDateTime createdAt) {
		Scholarship scholarship = Scholarship.builder()
				.title(title)
				.provider("테스트기관")
				.scholarshipType(type)
				.amount(2_000_000L)
				.applicationEndAt(endAt)
				.recruitmentStatus(RecruitmentStatus.OPEN)
				.build();
		ReflectionTestUtils.setField(scholarship, "id", id);
		ReflectionTestUtils.setField(scholarship, "createdAt", createdAt);
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

	private void stubScholarships(UserProfile profile, List<Scholarship> scholarships,
			List<ScholarshipCondition> conditions) {
		given(userProfileRepository.findByUserId(USER_ID)).willReturn(Optional.ofNullable(profile));
		given(scholarshipRepository.findAllOpenForRecommendation(
				org.mockito.ArgumentMatchers.eq(RecruitmentStatus.OPEN), org.mockito.ArgumentMatchers.any()))
				.willReturn(scholarships);
		if (!scholarships.isEmpty()) {
			given(scholarshipConditionRepository.findAllByScholarshipIn(scholarships)).willReturn(conditions);
		}
		// 기본: 스크랩 없음. 스크랩 케이스 테스트에서 개별 override.
		lenient().when(scrapRepository.findScrappedScholarshipIds(
						org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.anyList()))
				.thenReturn(List.of());
	}

	@Test
	@DisplayName("스크랩한 장학금은 카드의 isScrapped=true로 표시된다(featured/교내 모두)")
	void marksScrappedCards() {
		Scholarship scrapped = scholarship(1L, "스크랩함", ScholarshipType.INTERNAL,
				LocalDateTime.now().plusDays(10), LocalDateTime.now().minusDays(1));
		Scholarship notScrapped = scholarship(2L, "스크랩안함", ScholarshipType.INTERNAL,
				LocalDateTime.now().plusDays(20), LocalDateTime.now().minusDays(1));
		stubScholarships(null, List.of(scrapped, notScrapped), List.of());
		given(scrapRepository.findScrappedScholarshipIds(
						org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.anyList()))
				.willReturn(List.of(1L));

		CuratedScholarshipResponse response =
				scholarshipRecommendationService.getCuratedScholarships(USER_ID, 1, 10);

		// featured = 마감 가장 가까운 스크랩함(1L)
		assertThat(response.featured().scholarshipId()).isEqualTo(1L);
		assertThat(response.featured().isScrapped()).isTrue();
		// 교내 목록에서도 1L 만 true
		assertThat(response.campusScholarships())
				.filteredOn(card -> card.scholarshipId() == 1L).allMatch(ScholarshipCard::isScrapped);
		assertThat(response.campusScholarships())
				.filteredOn(card -> card.scholarshipId() == 2L).noneMatch(ScholarshipCard::isScrapped);
	}

	@Test
	@DisplayName("불충족 장학금은 eligible=false로 분류되어 그 외 목록 하단에 노출된다")
	void classifiesMismatchedAsIneligible() {
		Scholarship fits = scholarship(1L, "지원가능", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(30));
		Scholarship excluded = scholarship(2L, "분위초과", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(30));
		stubScholarships(UserProfile.builder().incomeLevel(5).build(), List.of(fits, excluded),
				List.of(incomeCondition(fits, 8), incomeCondition(excluded, 3)));

		CuratedScholarshipResponse response =
				scholarshipRecommendationService.getCuratedScholarships(USER_ID, 1, 10);

		assertThat(response.otherScholarships()).hasSize(1);
		assertThat(response.otherScholarships().get(0).scholarshipId()).isEqualTo(2L);
		assertThat(response.otherScholarships().get(0).eligible()).isFalse();
		// 지원 가능 1건은 마감이 가장 가까운 featured 카드로 승격된다
		assertThat(response.featured().scholarshipId()).isEqualTo(1L);
		assertThat(response.featured().matchReasons()).anyMatch(reason -> reason.contains("소득분위 충족"));
	}

	@Test
	@DisplayName("교내(INTERNAL) 장학금은 campusScholarships로 분리된다")
	void separatesCampusScholarships() {
		Scholarship campus = scholarship(1L, "교내성적우수", ScholarshipType.INTERNAL,
				LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(30));
		Scholarship external = scholarship(2L, "외부장학", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(3), LocalDateTime.now().minusDays(30));
		stubScholarships(UserProfile.builder().incomeLevel(3).build(), List.of(campus, external),
				List.of(incomeCondition(campus, 8), incomeCondition(external, 8)));

		CuratedScholarshipResponse response =
				scholarshipRecommendationService.getCuratedScholarships(USER_ID, 1, 10);

		assertThat(response.campusScholarships()).hasSize(1);
		assertThat(response.campusScholarships().get(0).scholarshipId()).isEqualTo(1L);
		assertThat(response.featured().scholarshipId()).isEqualTo(2L);
	}

	@Test
	@DisplayName("프로필이 없으면 배제 없이 전체가 노출되고 완성도는 0")
	void fallbackWithoutProfile() {
		Scholarship s1 = scholarship(1L, "장학금1", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(10), LocalDateTime.now().minusDays(30));
		stubScholarships(null, List.of(s1), List.of(incomeCondition(s1, 3)));

		CuratedScholarshipResponse response =
				scholarshipRecommendationService.getCuratedScholarships(USER_ID, 1, 10);

		assertThat(response.profileCompletionRate()).isZero();
		assertThat(response.featured()).isNotNull();
		assertThat(response.featured().eligible()).isTrue();
	}

	@Test
	@DisplayName("그 외 목록은 page/size로 페이지네이션된다")
	void paginatesOtherScholarships() {
		Scholarship s1 = scholarship(1L, "장1", ScholarshipType.EXTERNAL, null, LocalDateTime.now().minusDays(30));
		Scholarship s2 = scholarship(2L, "장2", ScholarshipType.EXTERNAL, null, LocalDateTime.now().minusDays(30));
		Scholarship s3 = scholarship(3L, "장3", ScholarshipType.EXTERNAL, null, LocalDateTime.now().minusDays(30));
		stubScholarships(UserProfile.builder().incomeLevel(3).build(), List.of(s1, s2, s3),
				List.of(incomeCondition(s1, 8), incomeCondition(s2, 8), incomeCondition(s3, 8)));

		CuratedScholarshipResponse response =
				scholarshipRecommendationService.getCuratedScholarships(USER_ID, 2, 1);

		// 마감일 없는 3건 -> featured 없음 -> others 3건 중 2페이지(1건씩)
		assertThat(response.featured()).isNull();
		assertThat(response.otherScholarships()).hasSize(1);
		assertThat(response.pagination().totalCount()).isEqualTo(3);
		assertThat(response.pagination().totalPages()).isEqualTo(3);
	}

	@Test
	@DisplayName("홈 요약: 신규(7일 이내 등록) 맞춤 건수와 D-7 마감 건수를 센다")
	void homeSummaryCounts() {
		Scholarship fresh = scholarship(1L, "신규", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(3), LocalDateTime.now().minusDays(1));
		Scholarship old = scholarship(2L, "오래됨", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(30));
		stubScholarships(UserProfile.builder().incomeLevel(5).build(), List.of(fresh, old),
				List.of(incomeCondition(fresh, 8), incomeCondition(old, 8)));

		HomeSummaryResponse summary = scholarshipRecommendationService.getHomeSummary(USER_ID);

		assertThat(summary.newMatchedCount()).isEqualTo(1);
		assertThat(summary.urgentDeadlineCount()).isEqualTo(1);
		assertThat(summary.hasNewMatched()).isTrue();
	}

	@Test
	@DisplayName("OPEN 장학금이 없으면 빈 응답")
	void emptyWhenNoOpenScholarships() {
		stubScholarships(null, List.of(), List.of());

		CuratedScholarshipResponse response =
				scholarshipRecommendationService.getCuratedScholarships(USER_ID, 1, 10);
		HomeSummaryResponse summary = scholarshipRecommendationService.getHomeSummary(USER_ID);

		assertThat(response.featured()).isNull();
		assertThat(response.otherScholarships()).isEmpty();
		assertThat(summary).isEqualTo(new HomeSummaryResponse(0, 0, false));
	}
}
