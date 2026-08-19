package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.wishconnect.domain.common.entity.School;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.common.repository.ImageRepository;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.insight.repository.InsightRepository;
import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse;
import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse.ScholarshipCard;
import com.wishconnect.domain.scholarship.dto.CuratedSort;
import com.wishconnect.domain.scholarship.dto.CuratedViewMode;
import com.wishconnect.domain.scholarship.dto.HomeSummaryResponse;
import com.wishconnect.domain.scholarship.entity.ConditionNecessity;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionRef;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.domain.user.entity.Interest;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.entity.UserInterest;
import com.wishconnect.domain.user.entity.UserProfile;
import com.wishconnect.domain.user.repository.UserFamilyTypeRepository;
import com.wishconnect.domain.user.repository.UserInterestRepository;
import com.wishconnect.domain.user.repository.UserProfileRepository;
import com.wishconnect.domain.user.repository.UserRepository;
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

	/** 조건이 마스터 ID 로 저장돼 있어 사용자 쪽 값도 ID 집합으로 필요하다. 기본값은 빈 목록. */
	@Mock
	private UserFamilyTypeRepository userFamilyTypeRepository;

	@Mock
	private UserInterestRepository userInterestRepository;

	@Mock
	private ScrapRepository scrapRepository;

	/** 포스터 조회용. 기본 스텁이 빈 목록을 돌려주므로 대부분 지정하지 않는다. */
	@Mock
	private ImageRepository imageRepository;

	@Mock
	private ImageStorageService imageStorageService;

	/** 홈 요약의 "작성 중인 지원서" 집계용. 기본 스텁이 0 을 돌려주므로 별도 지정은 필요 없다. */
	@Mock
	private EssayRepository essayRepository;

	/** 홈 요약의 "새로운 인사이트" 집계용. 위와 같은 이유로 기본값 0 을 그대로 쓴다. */
	@Mock
	private InsightRepository insightRepository;

	/** 인사말 이름 조회용. 이름이 없어도 카드는 그려져야 해서 대부분 비워 둔다. */
	@Mock
	private UserRepository userRepository;

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

	/**
	 * 개인화 응답을 보는 기본 스텁.
	 *
	 * <p>프로필을 넘기면 온보딩까지 끝난 것으로 본다. 온보딩 전에는 화면이 달라져
	 * (교내·조건미충족 섹션이 잠긴다) 추천 규칙을 검증할 수 없기 때문이다.
	 * 온보딩 전 화면은 {@code onboardingRequired...} 테스트에서 따로 본다.
	 */
	private void stubScholarships(UserProfile profile, List<Scholarship> scholarships,
			List<ScholarshipCondition> conditions) {
		if (profile != null) {
			profile.completeOnboarding();
		}
		given(userProfileRepository.findByUserId(USER_ID)).willReturn(Optional.ofNullable(profile));
		given(scholarshipRepository.findAllOpenForRecommendation(anyCollection(), any()))
				.willReturn(scholarships);
		if (!scholarships.isEmpty()) {
			// 온보딩 전 화면은 조건을 읽지 않으므로 lenient. 개인화 경로에서만 쓰인다.
			lenient().when(scholarshipConditionRepository.findAllByScholarshipIn(scholarships))
					.thenReturn(conditions);
		}
		// 기본: 스크랩 없음. 스크랩 케이스 테스트에서 개별 override.
		lenient().when(scrapRepository.findScrappedScholarshipIds(eq(USER_ID), anyList()))
				.thenReturn(List.of());
	}

	private CuratedScholarshipResponse curate() {
		return scholarshipRecommendationService.getCuratedScholarships(
				USER_ID, CuratedSort.DEADLINE, 1, 10);
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
	@DisplayName("우대사항은 안 맞아도 탈락시키지 않는다 — 게이트는 자격요건뿐이다")
	void preferredMismatchDoesNotExclude() {
		Scholarship target = scholarship(1L, "생활비 지원", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(30));

		// 자격요건은 충족하고, 우대사항(지원 성격)만 어긋난 상태.
		ScholarshipCondition required = incomeCondition(target, 8);
		ScholarshipCondition preferred = ScholarshipCondition.builder()
				.scholarship(target)
				.conditionType(ConditionType.FINANCIAL_AID_TYPE)
				.operator(ConditionOperator.EQ)
				.necessity(ConditionNecessity.PREFERRED)
				.valueString("생활비 지원")
				.autoExtracted(false)
				.build();
		preferred.applyRefs(List.of(ConditionRef.ofId(7L)));

		given(userInterestRepository.findAllByUserProfile_User_Id(USER_ID))
				.willReturn(List.of(userInterest(9L)));
		stubScholarships(UserProfile.builder().incomeLevel(5).build(), List.of(target),
				List.of(required, preferred));

		CuratedScholarshipResponse response = curate();

		assertThat(idsOf(response.ineligibleScholarships())).doesNotContain(1L);
		// 우대 불충족이 충족 비율에 반영돼 점수는 만점이 아니다.
		assertThat(response.featured().get(0).matchScore()).isLessThan(100);
	}

	private UserInterest userInterest(long interestId) {
		Interest interest = Interest.builder().name("등록금 지원").build();
		ReflectionTestUtils.setField(interest, "id", interestId);
		return UserInterest.builder().interest(interest).build();
	}

	@Test
	@DisplayName("featured 는 마감 임박순 최대 5건까지 배열로 내려간다")
	void featuredIsCarouselOfUpToFive() {
		List<Scholarship> scholarships = new java.util.ArrayList<>();
		for (int i = 1; i <= 7; i++) {
			scholarships.add(scholarship(i, "장학금" + i, ScholarshipType.EXTERNAL,
					LocalDateTime.now().plusDays(i), LocalDateTime.now().minusDays(30)));
		}
		stubScholarships(UserProfile.builder().build(), scholarships, List.of());

		CuratedScholarshipResponse response = curate();

		assertThat(response.featured()).hasSize(5);
		assertThat(idsOf(response.featured())).containsExactly(1L, 2L, 3L, 4L, 5L);
		assertThat(response.rankerVersion())
				.isEqualTo(com.wishconnect.domain.scholarship.util.ScholarshipRanker.RANKER_VERSION);
		assertThat(response.featured()).allMatch(card -> card.section().equals("featured"));
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
	@DisplayName("교내 장학금은 점수와 무관하게 마감 임박순으로 내려간다")
	void campusSortedByDeadline() {
		Scholarship later = scholarship(1L, "나중 마감", ScholarshipType.INTERNAL,
				LocalDateTime.now().plusDays(12), LocalDateTime.now(), "연세대학교");
		Scholarship sooner = scholarship(2L, "먼저 마감", ScholarshipType.INTERNAL,
				LocalDateTime.now().plusDays(3), LocalDateTime.now(), "연세대학교");
		stubScholarships(UserProfile.builder().school(school("연세대학교")).build(),
				List.of(later, sooner), List.of());

		assertThat(idsOf(curate().campusScholarships())).containsExactly(2L, 1L);
		assertThat(curate().campusScholarships()).allMatch(card -> card.section().equals("campus"));
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
	@DisplayName("온보딩 전에는 마감 임박 배너만 주고 나머지 섹션은 잠근다")
	void onboardingRequiredKeepsOnlyFeatured() {
		Scholarship campus = scholarship(1L, "교내", ScholarshipType.INTERNAL,
				LocalDateTime.now().plusDays(10), LocalDateTime.now().minusDays(30), "연세대학교");
		Scholarship external = scholarship(2L, "교외", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(20), LocalDateTime.now().minusDays(30));
		stubScholarships(null, List.of(campus, external), List.of(incomeCondition(external, 3)));

		CuratedScholarshipResponse response = curate();

		assertThat(response.viewMode()).isEqualTo(CuratedViewMode.ONBOARDING_REQUIRED);
		assertThat(idsOf(response.featured())).containsExactly(1L, 2L);
		// 화면에서 흐리게 잠기는 자리라 데이터를 싣지 않는다. "없음" 과의 구분은 viewMode 가 한다.
		assertThat(response.campusScholarships()).isEmpty();
		assertThat(response.otherScholarships()).isEmpty();
		assertThat(response.ineligibleScholarships()).isEmpty();
		assertThat(response.profileCompletionRate()).isZero();
	}

	@Test
	@DisplayName("온보딩 전에는 판정 근거가 없으므로 매칭 사유를 지어내지 않는다")
	void onboardingRequiredHasNoMatchReasons() {
		Scholarship external = scholarship(1L, "교외", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(20), LocalDateTime.now().minusDays(30));
		stubScholarships(null, List.of(external), List.of(incomeCondition(external, 3)));

		assertThat(curate().featured()).allSatisfy(card -> {
			assertThat(card.matchReasons()).isEmpty();
			assertThat(card.matchScore()).isZero();
		});
	}

	@Test
	@DisplayName("프로필은 있지만 온보딩을 안 끝냈으면 개인화하지 않는다")
	void onboardingRequiredWhenProfileIncomplete() {
		Scholarship mine = scholarship(1L, "교내", ScholarshipType.INTERNAL,
				LocalDateTime.now().plusDays(10), LocalDateTime.now().minusDays(30), "연세대학교");
		// completeOnboarding() 을 부르지 않은 프로필 = STEP 진행 중
		UserProfile inProgress = UserProfile.builder().school(school("연세대학교")).build();
		given(userProfileRepository.findByUserId(USER_ID)).willReturn(Optional.of(inProgress));
		given(scholarshipRepository.findAllOpenForRecommendation(anyCollection(), any()))
				.willReturn(List.of(mine));
		lenient().when(scrapRepository.findScrappedScholarshipIds(eq(USER_ID), anyList()))
				.thenReturn(List.of());

		CuratedScholarshipResponse response = curate();

		assertThat(response.viewMode()).isEqualTo(CuratedViewMode.ONBOARDING_REQUIRED);
		// 학교를 알고 있어도 온보딩이 안 끝났으면 교내 섹션은 잠긴 채로 둔다
		assertThat(response.campusScholarships()).isEmpty();
	}

	// ---------- 비로그인 ----------

	@Test
	@DisplayName("비로그인은 추천 없이 마감 임박순으로만 준다")
	void guestSortsByDeadline() {
		Scholarship late = scholarship(1L, "늦게 마감", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(1));
		Scholarship soon = scholarship(2L, "곧 마감", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(2), LocalDateTime.now().minusDays(30));
		given(scholarshipRepository.findAllOpenForRecommendation(anyCollection(), any()))
				.willReturn(List.of(late, soon));

		CuratedScholarshipResponse response = scholarshipRecommendationService
				.getCuratedScholarships(null, CuratedSort.DEADLINE, 1, 10);

		assertThat(response.viewMode()).isEqualTo(CuratedViewMode.GUEST);
		assertThat(idsOf(response.otherScholarships())).containsExactly(2L, 1L);
		// 비로그인 화면에는 히어로 배너도, 교내/조건미충족 섹션도 없다
		assertThat(response.featured()).isEmpty();
		assertThat(response.campusScholarships()).isEmpty();
		assertThat(response.ineligibleScholarships()).isEmpty();
	}

	@Test
	@DisplayName("비로그인 최신 등록순은 등록이 늦은 것부터 준다")
	void guestSortsByLatest() {
		Scholarship old = scholarship(1L, "옛날 등록", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(2), LocalDateTime.now().minusDays(30));
		Scholarship fresh = scholarship(2L, "최근 등록", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(30), LocalDateTime.now().minusDays(1));
		given(scholarshipRepository.findAllOpenForRecommendation(anyCollection(), any()))
				.willReturn(List.of(old, fresh));

		CuratedScholarshipResponse response = scholarshipRecommendationService
				.getCuratedScholarships(null, CuratedSort.LATEST, 1, 10);

		assertThat(idsOf(response.otherScholarships())).containsExactly(2L, 1L);
	}

	@Test
	@DisplayName("비로그인은 마감일 없는 공고를 뒤로 밀되 빠뜨리지는 않는다")
	void guestKeepsScholarshipsWithoutDeadlineAtTheEnd() {
		Scholarship noDeadline = scholarship(1L, "마감 미상", ScholarshipType.EXTERNAL,
				null, LocalDateTime.now().minusDays(30));
		Scholarship dated = scholarship(2L, "마감 있음", ScholarshipType.EXTERNAL,
				LocalDateTime.now().plusDays(5), LocalDateTime.now().minusDays(30));
		given(scholarshipRepository.findAllOpenForRecommendation(anyCollection(), any()))
				.willReturn(List.of(noDeadline, dated));

		CuratedScholarshipResponse response = scholarshipRecommendationService
				.getCuratedScholarships(null, CuratedSort.DEADLINE, 1, 10);

		// 마감일 파싱이 안 된 공고가 많아, 뒤로 미는 것과 버리는 것은 결과가 크게 다르다
		assertThat(idsOf(response.otherScholarships())).containsExactly(2L, 1L);
	}

	@Test
	@DisplayName("비로그인 목록도 page/size 로 페이지네이션된다")
	void guestPaginates() {
		List<Scholarship> pool = new java.util.ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			pool.add(scholarship(i, "장학금" + i, ScholarshipType.EXTERNAL,
					LocalDateTime.now().plusDays(i), LocalDateTime.now().minusDays(30)));
		}
		given(scholarshipRepository.findAllOpenForRecommendation(anyCollection(), any()))
				.willReturn(pool);

		CuratedScholarshipResponse response = scholarshipRecommendationService
				.getCuratedScholarships(null, CuratedSort.DEADLINE, 2, 2);

		assertThat(idsOf(response.otherScholarships())).containsExactly(3L);
		assertThat(response.pagination().totalCount()).isEqualTo(3);
		assertThat(response.pagination().totalPages()).isEqualTo(2);
	}

	@Test
	@DisplayName("비로그인은 조건 데이터를 아예 읽지 않는다")
	void guestSkipsConditionLookup() {
		given(scholarshipRepository.findAllOpenForRecommendation(anyCollection(), any()))
				.willReturn(List.of(scholarship(1L, "장학금", ScholarshipType.EXTERNAL,
						LocalDateTime.now().plusDays(5), LocalDateTime.now().minusDays(30))));

		scholarshipRecommendationService.getCuratedScholarships(null, CuratedSort.DEADLINE, 1, 10);

		// 프로필이 없으면 모든 조건이 판정 불가라 점수가 전부 같아진다. 읽어도 쓸 데가 없다.
		verify(scholarshipConditionRepository, never()).findAllByScholarshipIn(anyList());
		verifyNoInteractions(scrapRepository);
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
				scholarshipRecommendationService.getCuratedScholarships(
						USER_ID, CuratedSort.DEADLINE, 2, 1);

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
	@DisplayName("홈 요약: 인사말 이름과 새로운 인사이트 건수를 함께 준다")
	void homeSummaryCarriesNameAndInsightCount() {
		stubScholarships(null, List.of(), List.of());
		given(userRepository.findById(USER_ID)).willReturn(Optional.of(
				User.createLocal("u@example.com", "user01", "encoded", "김위시", "010-1111-2222")));
		given(insightRepository.countByCreatedAtAfter(any())).willReturn(3L);

		HomeSummaryResponse summary = scholarshipRecommendationService.getHomeSummary(USER_ID);

		assertThat(summary.userName()).isEqualTo("김위시");
		assertThat(summary.newInsightCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("홈 요약: 회원 정보를 못 찾아도 이름만 비우고 카드는 내려준다")
	void homeSummaryToleratesMissingUser() {
		stubScholarships(null, List.of(), List.of());
		given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

		HomeSummaryResponse summary = scholarshipRecommendationService.getHomeSummary(USER_ID);

		assertThat(summary.userName()).isNull();
		assertThat(summary.newMatchedCount()).isZero();
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
		assertThat(summary).isEqualTo(new HomeSummaryResponse(null, 0, 0, 0, 0, false));
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
