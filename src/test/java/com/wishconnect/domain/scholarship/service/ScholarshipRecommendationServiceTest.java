package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.wishconnect.domain.common.entity.School;
import com.wishconnect.domain.application.repository.EssayRepository;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

	/** 홈 요약의 "작성 중인 지원서" 집계용. 기본 스텁이 0 을 돌려주므로 별도 지정은 필요 없다. */
	@Mock
	private EssayRepository essayRepository;

	@InjectMocks
	private ScholarshipRecommendationService scholarshipRecommendationService;

	// ---------- 픽스처 ----------

	private Scholarship scholarship(long id, String title, ScholarshipType type, LocalDateTime endAt,
			LocalDateTime createdAt) {
		return scholarship(id, title, type, endAt, createdAt, "테스트기관");
	}

	private Scholarship scholarship(long id, String title, ScholarshipType type, LocalDateTime endAt,
			LocalDateTime createdAt, String provider) {
		Scholarship scholarship = Scholarship.builder()
				.title(title)
				.provider(provider)
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
		return condition(scholarship, ConditionType.INCOME_CRITERIA, ConditionOperator.LTE, maxLevel,
				"소득 " + maxLevel + "분위 이하");
	}

	/** 성적 조건. ConditionMatcher 는 valueInt 를 평점×100(3.5 -> 350)으로 읽는다. */
	private ScholarshipCondition gpaCondition(Scholarship scholarship, String minGpa) {
		int times100 = new BigDecimal(minGpa).movePointRight(2).intValue();
		return condition(scholarship, ConditionType.ACADEMIC_CRITERIA, ConditionOperator.GTE, times100,
				"학점 " + minGpa + " 이상");
	}

	private ScholarshipCondition condition(Scholarship scholarship, ConditionType type,
			ConditionOperator operator, Integer valueInt, String label) {
		return ScholarshipCondition.builder()
				.scholarship(scholarship)
				.conditionType(type)
				.operator(operator)
				.valueInt(valueInt)
				.valueString(label)
				.autoExtracted(false)
				.build();
	}

	private School school(String name) {
		return School.builder().name(name).build();
	}

	private void stubScholarships(UserProfile profile, List<Scholarship> scholarships,
			List<ScholarshipCondition> conditions) {
		given(userProfileRepository.findByUserId(USER_ID)).willReturn(Optional.ofNullable(profile));
		given(scholarshipRepository.findAllOpenForRecommendation(eq(RecruitmentStatus.OPEN), any()))
				.willReturn(scholarships);
		if (!scholarships.isEmpty()) {
			given(scholarshipConditionRepository.findAllByScholarshipIn(scholarships)).willReturn(conditions);
		}
		// 기본: 스크랩 없음. 스크랩 케이스 테스트에서 개별 override.
		lenient().when(scrapRepository.findScrappedScholarshipIds(eq(USER_ID), anyList()))
				.thenReturn(List.of());
	}

	private CuratedScholarshipResponse curate() {
		return scholarshipRecommendationService.getCuratedScholarships(USER_ID, 1, 10);
	}

	private static List<Long> idsOf(List<ScholarshipCard> cards) {
		return cards.stream().map(ScholarshipCard::scholarshipId).toList();
	}

	// ---------- 기본 동작 ----------

	@Test
	@DisplayName("스크랩한 장학금은 카드의 isScrapped=true로 표시된다(featured/교내 모두)")
	void marksScrappedCards() {
		Scholarship scrapped = scholarship(1L, "스크랩함", ScholarshipType.INTERNAL,
				LocalDateTime.now().plusDays(10), LocalDateTime.now().minusDays(1), "연세대학교");
		Scholarship notScrapped = scholarship(2L, "스크랩안함", ScholarshipType.INTERNAL,
				LocalDateTime.now().plusDays(20), LocalDateTime.now().minusDays(1), "연세대학교");
		stubScholarships(UserProfile.builder().school(school("연세대학교")).build(),
				List.of(scrapped, notScrapped), List.of());
		given(scrapRepository.findScrappedScholarshipIds(eq(USER_ID), anyList())).willReturn(List.of(1L));

		CuratedScholarshipResponse response = curate();

		assertThat(response.featured().get(0).scholarshipId()).isEqualTo(1L);
		assertThat(response.featured().get(0).isScrapped()).isTrue();
		assertThat(response.campusScholarships())
				.filteredOn(card -> card.scholarshipId() == 1L).allMatch(ScholarshipCard::isScrapped);
		assertThat(response.campusScholarships())
				.filteredOn(card -> card.scholarshipId() == 2L).noneMatch(ScholarshipCard::isScrapped);
	}

	@Test
	@DisplayName("조건 미충족 장학금은 otherScholarships 가 아니라 ineligibleScholarships 로 분리된다")
	void separatesIneligibleIntoOwnSection() {
		Scholarship fits = scholarship(1L, "지원가능", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(30));
		Scholarship excluded = scholarship(2L, "분위초과", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(30));
		stubScholarships(UserProfile.builder().incomeLevel(5).build(), List.of(fits, excluded),
				List.of(incomeCondition(fits, 8), incomeCondition(excluded, 3)));

		CuratedScholarshipResponse response = curate();

		// 조건 미충족은 그 외 목록에 섞이지 않는다
		assertThat(idsOf(response.otherScholarships())).doesNotContain(2L);
		assertThat(idsOf(response.ineligibleScholarships())).containsExactly(2L);
		assertThat(response.ineligibleScholarships().get(0).eligible()).isFalse();
		// 지원 가능 1건은 마감 임박 배너로 올라간다
		assertThat(idsOf(response.featured())).containsExactly(1L);
		assertThat(response.featured().get(0).matchReasons()).anyMatch(r -> r.contains("소득분위 충족"));
	}

	@Test
	@DisplayName("featured 는 마감 임박순 최대 5건까지 배열로 내려간다")
	void featuredIsCarouselOfUpToFive() {
		List<Scholarship> scholarships = new java.util.ArrayList<>();
		for (int i = 1; i <= 7; i++) {
			scholarships.add(scholarship(i, "장학금" + i, ScholarshipType.EXTERNAL,
					LocalDateTime.now().plusDays(i), LocalDateTime.now().minusDays(30)));
		}
		stubScholarships(null, scholarships, List.of());

		CuratedScholarshipResponse response = curate();

		assertThat(response.featured()).hasSize(5);
		assertThat(idsOf(response.featured())).containsExactly(1L, 2L, 3L, 4L, 5L);
		// featured 로 올라간 건은 그 외 목록에서 중복 노출되지 않는다
		assertThat(idsOf(response.otherScholarships())).containsExactlyInAnyOrder(6L, 7L);
	}

	@Test
	@DisplayName("교내 장학금은 소속 학교의 것만 노출된다")
	void campusFilteredByUserSchool() {
		Scholarship mine = scholarship(1L, "교내-우리학교", ScholarshipType.INTERNAL,
				LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(30), "연세대학교");
		Scholarship others = scholarship(2L, "교내-남의학교", ScholarshipType.INTERNAL,
				LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(30), "건국대학교");
		stubScholarships(UserProfile.builder().school(school("연세대학교")).build(),
				List.of(mine, others), List.of());

		CuratedScholarshipResponse response = curate();

		assertThat(idsOf(response.campusScholarships())).containsExactly(1L);
	}

	@Test
	@DisplayName("학교 표기가 '연세대'/'연세대학교' 로 달라도 같은 학교로 본다")
	void campusMatchToleratesNameVariants() {
		Scholarship mine = scholarship(1L, "교내", ScholarshipType.INTERNAL,
				LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(30), "연세대학교");
		stubScholarships(UserProfile.builder().school(school("연세대")).build(), List.of(mine), List.of());

		assertThat(idsOf(curate().campusScholarships())).containsExactly(1L);
	}

	@Test
	@DisplayName("프로필이 없으면 배제 없이 노출되고 완성도는 0, 교내는 판단 불가라 비어 있다")
	void fallbackWithoutProfile() {
		Scholarship campus = scholarship(1L, "교내", ScholarshipType.INTERNAL,
				LocalDateTime.now().plusDays(10), LocalDateTime.now().minusDays(30), "연세대학교");
		Scholarship external = scholarship(2L, "교외", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(20), LocalDateTime.now().minusDays(30));
		stubScholarships(null, List.of(campus, external), List.of(incomeCondition(external, 3)));

		CuratedScholarshipResponse response = curate();

		assertThat(response.profileCompletionRate()).isZero();
		assertThat(response.featured()).isNotEmpty();
		assertThat(response.featured()).allMatch(ScholarshipCard::eligible);
		assertThat(response.ineligibleScholarships()).isEmpty();
		// 소속 학교를 모르므로 교내 섹션은 비운다(남의 학교 장학금 노출 방지)
		assertThat(response.campusScholarships()).isEmpty();
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

		// 마감일이 없으면 featured 대상이 아니다 -> others 3건 중 2페이지
		assertThat(response.featured()).isEmpty();
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

		CuratedScholarshipResponse response = curate();
		HomeSummaryResponse summary = scholarshipRecommendationService.getHomeSummary(USER_ID);

		assertThat(response.featured()).isEmpty();
		assertThat(response.otherScholarships()).isEmpty();
		assertThat(response.ineligibleScholarships()).isEmpty();
		assertThat(summary).isEqualTo(new HomeSummaryResponse(0, 0, 0, false));
	}

	/**
	 * 실제 사용자 상황을 흉내낸 페르소나 검증.
	 *
	 * <p>같은 장학금 풀을 서로 다른 프로필에 태워, 사람마다 결과가 실제로 달라지는지
	 * (= 추천이 프로필을 반영하는지) 확인한다. 단위 테스트 각각은 규칙 하나를 보지만
	 * 이 묶음은 "화면에 무엇이 뜨는가"를 본다.
	 */
	@Nested
	@DisplayName("페르소나 검증")
	class PersonaScenarios {

		/** 모든 페르소나가 같은 장학금 풀을 본다. */
		private List<Scholarship> pool() {
			return List.of(
					scholarship(1L, "연세 교내 성적우수", ScholarshipType.INTERNAL,
							LocalDateTime.now().plusDays(5), LocalDateTime.now().minusDays(2), "연세대학교"),
					scholarship(2L, "건국 교내 생활비", ScholarshipType.INTERNAL,
							LocalDateTime.now().plusDays(6), LocalDateTime.now().minusDays(2), "건국대학교"),
					scholarship(3L, "저소득층 생활비", ScholarshipType.EXTERNAL,
							LocalDateTime.now().plusDays(3), LocalDateTime.now().minusDays(2), "OO장학재단"),
					scholarship(4L, "성적우수 등록금", ScholarshipType.EXTERNAL,
							LocalDateTime.now().plusDays(40), LocalDateTime.now().minusDays(2), "XX재단"),
					scholarship(5L, "근로장학", ScholarshipType.WORK_STUDY,
							LocalDateTime.now().plusDays(10), LocalDateTime.now().minusDays(2), "연세대학교"));
		}

		private List<ScholarshipCondition> conditions(List<Scholarship> pool) {
			return List.of(
					gpaCondition(pool.get(0), "3.5"),      // 1L 교내: 학점 3.5 이상
					incomeCondition(pool.get(1), 8),       // 2L 교내: 소득 8분위 이하
					incomeCondition(pool.get(2), 3),       // 3L 저소득: 소득 3분위 이하
					gpaCondition(pool.get(3), "4.0"));     // 4L 성적우수: 학점 4.0 이상
		}

		private CuratedScholarshipResponse runAs(UserProfile profile) {
			List<Scholarship> pool = pool();
			stubScholarships(profile, pool, conditions(pool));
			return curate();
		}

		@Test
		@DisplayName("페르소나A 연세대·학점4.2·소득2분위 → 교내는 연세 것만, 저소득/성적우수 모두 지원 가능")
		void personaA_highGpaLowIncomeYonsei() {
			CuratedScholarshipResponse response = runAs(UserProfile.builder()
					.school(school("연세대학교"))
					.cumulativeGpa(new BigDecimal("4.2"))
					.incomeLevel(2)
					.build());

			// 교내: 연세(1L)만. 건국(2L)은 조건은 맞아도 남의 학교라 빠진다.
			assertThat(idsOf(response.campusScholarships())).containsExactly(1L);
			// 조건을 모두 만족하므로 미충족 섹션은 비어 있다
			assertThat(response.ineligibleScholarships()).isEmpty();
			// 근로장학은 추천 목록에 넣지 않는다
			assertThat(idsOf(response.otherScholarships())).doesNotContain(5L);
			assertThat(idsOf(response.featured())).doesNotContain(5L);
		}

		@Test
		@DisplayName("페르소나B 건국대·학점2.8·소득7분위 → 성적 조건 장학금은 미충족으로 분리")
		void personaB_lowGpaMidIncomeKonkuk() {
			CuratedScholarshipResponse response = runAs(UserProfile.builder()
					.school(school("건국대학교"))
					.cumulativeGpa(new BigDecimal("2.8"))
					.incomeLevel(7)
					.build());

			// 교내: 건국(2L)만 — 소득 8분위 이하라 충족
			assertThat(idsOf(response.campusScholarships())).containsExactly(2L);
			// 학점 미달(1L 3.5, 4L 4.0) + 소득 초과(3L 3분위) 는 미충족으로 분리
			assertThat(idsOf(response.ineligibleScholarships())).contains(3L, 4L);
			// 미충족 건이 지원 가능 목록을 오염시키지 않는다
			assertThat(idsOf(response.otherScholarships())).doesNotContainAnyElementsOf(List.of(3L, 4L));
		}

		@Test
		@DisplayName("페르소나C 온보딩 미완(학교만 입력) → 판정 불가라 배제하지 않고, 완성도는 낮게 나온다")
		void personaC_partialProfile() {
			CuratedScholarshipResponse response = runAs(UserProfile.builder()
					.school(school("연세대학교"))
					.build());

			// 학점·소득을 모르면 탈락시키지 않는다(판정불가는 탈락 사유가 아님)
			assertThat(response.ineligibleScholarships()).isEmpty();
			assertThat(idsOf(response.campusScholarships())).containsExactly(1L);
			assertThat(response.profileCompletionRate()).isLessThan(50);
		}

		@Test
		@DisplayName("같은 장학금 풀이라도 페르소나에 따라 결과가 달라진다(추천이 프로필을 반영)")
		void personasSeeDifferentResults() {
			CuratedScholarshipResponse a = runAs(UserProfile.builder()
					.school(school("연세대학교"))
					.cumulativeGpa(new BigDecimal("4.2"))
					.incomeLevel(2)
					.build());
			List<Long> aCampus = idsOf(a.campusScholarships());
			List<Long> aIneligible = idsOf(a.ineligibleScholarships());

			CuratedScholarshipResponse b = runAs(UserProfile.builder()
					.school(school("건국대학교"))
					.cumulativeGpa(new BigDecimal("2.8"))
					.incomeLevel(7)
					.build());

			assertThat(idsOf(b.campusScholarships())).isNotEqualTo(aCampus);
			assertThat(idsOf(b.ineligibleScholarships())).isNotEqualTo(aIneligible);
		}
	}
}
