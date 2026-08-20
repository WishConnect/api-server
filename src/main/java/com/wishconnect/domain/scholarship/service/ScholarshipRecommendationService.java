package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.application.entity.EssayStatus;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.common.repository.ImageRepository;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.insight.repository.InsightRepository;
import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse;
import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse.Pagination;
import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse.ScholarshipCard;
import com.wishconnect.domain.scholarship.dto.CuratedSort;
import com.wishconnect.domain.scholarship.dto.CuratedFilters;
import com.wishconnect.domain.scholarship.dto.DeadlineFilter;
import com.wishconnect.domain.scholarship.dto.CuratedViewMode;
import com.wishconnect.domain.scholarship.dto.HomeSummaryResponse;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.domain.scholarship.entity.ConditionNecessity;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.util.ConditionMatcher;
import com.wishconnect.domain.scholarship.util.MatchProfile;
import com.wishconnect.domain.scholarship.util.ScholarshipRanker;
import com.wishconnect.domain.scholarship.util.ConditionMatcher.Evaluation;
import com.wishconnect.domain.scholarship.util.ConditionMatcher.Result;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.entity.UserProfile;
import com.wishconnect.domain.user.repository.UserFamilyTypeRepository;
import com.wishconnect.domain.user.repository.UserInterestRepository;
import com.wishconnect.domain.user.repository.UserProfileRepository;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
큐레이팅 화면을 만든다. 화면은 로그인·온보딩 여부에 따라 셋으로 갈린다(CuratedViewMode).

- GUEST: 추천 없음. 지금 지원 가능한 공고를 정렬 드롭다운(최신 등록순/마감 임박순)대로만 준다.
- ONBOARDING_REQUIRED: 마감 임박 배너까지만. 교내·조건미충족 섹션은 화면에서 잠기므로 비운다.
- PERSONALIZED: 아래 프로필 룰 기반 추천(Phase 1).

PERSONALIZED 의 규칙:
- 조건별 판정은 충족/불충족/판정불가 3값(ConditionMatcher). 판정불가는 탈락 사유로 쓰지 않는다.
- 게이트는 자격요건(REQUIRED)의 불충족뿐이다. 우대사항(PREFERRED)은 안 맞아도 지원할 수 있어
  탈락시키지 않고 순위만 낮춘다. 이 구분이 없으면 조건을 성실히 채울수록 추천이 비어간다 —
  공고문에는 자격요건만큼 우대사항이 많다.
- 점수 = 충족 비율(최대 70) + 판정 가능 조건 존재 가점(10) + 마감 임박 가점(20).
- featured = 지원 가능 공고 전체(점수순, 동점은 마감순). campus = 소속 학교 키워드를 포함한 교내(INTERNAL).

조건 데이터가 아예 없는 공고는 여전히 전부 동점이다. 랭킹 방식 자체는 별도로 검토 중이다.
 */
@Service
@RequiredArgsConstructor
public class ScholarshipRecommendationService {

	/**
	 * 목록에 보이는 상태.
	 *
	 * <p>{@code ALWAYS_OPEN} 은 마감일이 없어 자동 판정을 포기한 공고다("충원 시 마감").
	 * 열려 있는 건 사실이므로 숨기지 않는다 — 상태를 나눈 이유는 관리자가 확인하기 위해서지
	 * 사용자에게서 감추기 위해서가 아니다.
	 */
	private static final java.util.Set<RecruitmentStatus> VISIBLE_STATUSES =
			java.util.EnumSet.of(RecruitmentStatus.OPEN, RecruitmentStatus.ALWAYS_OPEN);

	private static final int DEADLINE_SOON_DAYS = 7;
	/** featured 전체 중 other 에서 제외할 첫 노출 개수. 프론트의 초기 카드 개수와 맞춘다. */
	private static final int FEATURED_LIMIT = 5;
	private static final String SECTION_FEATURED = "featured";
	private static final String SECTION_CAMPUS = "campus";
	private static final String SECTION_OTHER = "other";
	private static final String SECTION_INELIGIBLE = "ineligible";
	private static final int NEW_MATCHED_DAYS = 7;

	private final ScholarshipRepository scholarshipRepository;
	// 홈 요약의 "작성 중인 지원서" 칸. 도메인은 다르지만 집계 한 줄이라 별도 서비스를 두지 않는다.
	private final EssayRepository essayRepository;
	// 같은 이유로 "새로운 인사이트" 칸도 여기서 센다.
	private final InsightRepository insightRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final UserProfileRepository userProfileRepository;
	// 조건이 마스터 ID 로 저장돼 있어(scholarship_condition_ref) 사용자 쪽 값도 ID 집합으로 필요하다.
	private final UserFamilyTypeRepository userFamilyTypeRepository;
	private final UserInterestRepository userInterestRepository;
	private final UserRepository userRepository;
	private final ScrapRepository scrapRepository;
	// 카드 그리드가 포스터 중심이라 목록에서도 이미지를 함께 내려준다.
	private final ImageRepository imageRepository;
	private final ImageStorageService imageStorageService;
	// 사는 곳·다니는 학교가 다른 공고를 걷어내는 관문. 자격 판정과 분리해 둔다.
	private final ScholarshipEligibilityGate eligibilityGate;

	/**
	 * 큐레이팅 메인. 로그인·온보딩 여부에 따라 세 가지 화면 중 하나를 만든다.
	 *
	 * @param userId 비로그인이면 {@code null}
	 * @param sort   비로그인 화면의 정렬 드롭다운. 나머지 상태에서는 화면에 드롭다운이 없어 쓰이지 않는다.
	 */
	@Transactional(readOnly = true)
	public CuratedScholarshipResponse getCuratedScholarships(
			UUID userId, CuratedSort sort, int page, int size) {
		return getCuratedScholarships(userId, sort, page, size, CuratedFilters.none());
	}

	@Transactional(readOnly = true)
	public CuratedScholarshipResponse getCuratedScholarships(
			UUID userId, CuratedSort sort, int page, int size, CuratedFilters filters) {
		if (filters.minAmount() != null && filters.maxAmount() != null
				&& filters.minAmount() > filters.maxAmount()) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}

		if (userId == null) {
			return guestCurated(sort, page, size);
		}
		UserProfile profile = userProfileRepository.findByUserId(userId).orElse(null);
		if (profile == null || !profile.isOnboardingCompleted()) {
			return onboardingRequiredCurated(userId);
		}
		return personalizedCurated(userId, profile, page, size, filters);
	}

	/**
	 * 비로그인 화면. 추천 로직을 태우지 않는다.
	 *
	 * <p>프로필이 없으면 모든 조건이 "판정 불가" 라 점수가 전부 0 으로 같아진다. 즉 채점을 해도
	 * 정렬에 아무 영향이 없으면서 조건 조회만 낭비된다. 그래서 조건을 아예 읽지 않고
	 * 정렬 기준만 적용한다.
	 */
	private CuratedScholarshipResponse guestCurated(CuratedSort sort, int page, int size) {
		List<Scholarship> open = scholarshipRepository.findAllOpenForRecommendation(
				VISIBLE_STATUSES, LocalDateTime.now());

		List<Scholarship> sorted = open.stream().sorted(comparatorFor(sort)).toList();
		Page<Scholarship> paged = slice(sorted, page, size);
		Map<Long, String> posters = findPosterUrls(
				paged.items().stream().map(Scholarship::getId).toList());

		// 비로그인은 스크랩 상태가 없고(로그인해야 스크랩할 수 있다) 판정 근거도 없다.
		List<ScholarshipCard> cards = paged.items().stream()
				.map(s -> ScholarshipCard.of(SECTION_OTHER, s, posters.get(s.getId()), 0, List.of(), true, false))
				.toList();

		return new CuratedScholarshipResponse(CuratedViewMode.GUEST, ScholarshipRanker.RANKER_VERSION,
				List.of(), 0, 0, List.of(), List.of(), cards, paged.pagination());
	}

	/**
	 * 로그인했지만 온보딩 미완료. 마감 임박 배너까지만 채우고 나머지 섹션은 비운다.
	 *
	 * <p>교내·조건미충족 섹션은 화면에서 흐리게 잠기고 "프로필 업데이트하고 확인하기" 가 덮인다.
	 * 잠긴 자리에 실을 데이터를 굳이 내려보내지 않는다. 빈 배열이 "없음" 인지 "잠김" 인지는
	 * {@code viewMode} 로 구분한다.
	 */
	private CuratedScholarshipResponse onboardingRequiredCurated(UUID userId) {
		List<Scholarship> open = scholarshipRepository.findAllOpenForRecommendation(
				VISIBLE_STATUSES, LocalDateTime.now());

		// 근로장학은 추천 성격이 아니라 히어로 배너에 올리지 않는다(온보딩 완료 화면과 같은 기준).
		List<Scholarship> featured = open.stream()
				.filter(s -> s.getScholarshipType() != ScholarshipType.WORK_STUDY)
				.filter(s -> {
					Long dDay = CuratedScholarshipResponse.calculateDday(s.getApplicationEndAt());
					return dDay != null && dDay >= 0;
				})
				.sorted(comparatorFor(CuratedSort.DEADLINE))
				.limit(FEATURED_LIMIT)
				.toList();

		List<Long> featuredIds = featured.stream().map(Scholarship::getId).toList();
		Set<Long> scrappedIds = findScrappedIds(userId, featuredIds);
		Map<Long, String> posters = findPosterUrls(featuredIds);

		List<ScholarshipCard> cards = featured.stream()
				.map(s -> ScholarshipCard.of(SECTION_FEATURED, s, posters.get(s.getId()), 0, List.of(), true,
						scrappedIds.contains(s.getId())))
				.toList();

		return new CuratedScholarshipResponse(CuratedViewMode.ONBOARDING_REQUIRED, ScholarshipRanker.RANKER_VERSION,
				cards, 0, 0, List.of(), List.of(), List.of(), new Pagination(1, 0, 0, 0));
	}

	private CuratedScholarshipResponse personalizedCurated(
			UUID userId, UserProfile profile, int page, int size, CuratedFilters filters) {
		MatchProfile matchProfile = matchProfileOf(userId, profile);
		List<ScoredScholarship> scored = scoreOpenScholarships(matchProfile);

		// 화면에 노출되는 카드(featured/교내/그외)의 스크랩 여부를 한 번에 조회한다.
		// 상세·검색과 달리 큐레이팅 카드에 isScrapped 가 없어, 뒤로가기 시 스크랩 상태가 사라지던 문제 해결.
		Set<Long> scrappedIds = findScrappedIds(userId,
				scored.stream().map(s -> s.scholarship().getId()).toList());
		Map<Long, String> posters = findPosterUrls(
				scored.stream().map(s -> s.scholarship().getId()).toList());

		// 사는 곳·다니는 학교가 다른 공고를 먼저 걷어낸다.
		//
		// 타입(INTERNAL)으로 거르던 방식은 새어 나갔다. 학교를 짚는 공고 17건 중 16건이
		// INTERNAL 이 아니라 WORK_STUDY(9)·EXTERNAL(7) 로 분류돼 있어, 인천대 근로장학금이
		// 그대로 통과했다. 그래서 타입이 아니라 <학교 id·조건·제목> 을 본다.
		//
		// 자격 게이트(eligible)와 따로 거는 이유도 있다. 자격 게이트는 우대사항(PREFERRED)과
		// 통합 공고(combined)를 봐주는데, 사는 곳·다니는 학교는 봐주면 안 된다.
		List<ScoredScholarship> eligibleList = scored.stream()
				.filter(ScoredScholarship::eligible)
				.filter(s -> eligibilityGate.belongsTo(s.scholarship(), s.conditions(), matchProfile))
				.toList();

		// 프론트가 처음 5건과 더보기를 나누므로, 지원 가능한 전체를 정렬해 내려준다.
		// 교내·교외·근로를 모두 포함하고 점수 동점일 때만 마감일을 본다.
		List<ScoredScholarship> featured = eligibleList.stream()
				.sorted(recommendationComparator()).toList();
		Set<Long> featuredTopIds = featured.stream().limit(FEATURED_LIMIT)
				.map(s -> s.scholarship().getId())
				.collect(Collectors.toSet());

		// 교내는 소속 학교 것만 노출한다. 학교 정보가 없으면 판단할 수 없어 비운다.
		// 타입이 INTERNAL 이 아니어도(근로장학이 대표적) 학교가 지정돼 있으면 교내로 본다.
		List<ScholarshipCard> campus = eligibleList.stream()
				.filter(s -> s.scholarship().getScholarshipType() == ScholarshipType.INTERNAL
						|| s.scholarship().getSchool() != null)
				.filter(s -> isSameSchool(s.scholarship(), profile))
				.sorted(recommendationComparator())
				.map(s -> s.toCard(SECTION_CAMPUS, scrappedIds, posters))
				.toList();

		// 그 외 추천: featured 첫 5건을 제외한 나머지 지원 가능 장학금.
		List<ScoredScholarship> otherRanked = eligibleList.stream()
				.filter(s -> !featuredTopIds.contains(s.scholarship().getId()))
				.filter(s -> matchesFilters(s, filters, scrappedIds))
				.sorted(recommendationComparator())
				.toList();
		List<ScholarshipCard> others = otherRanked.stream()
				.map(s -> s.toCard(SECTION_OTHER, scrappedIds, posters))
				.toList();

		// 조건 미충족은 모집 중인 전체 장학금을 대상으로 하며 근로도 제외하지 않는다.
		// 다만 사는 곳·다니는 학교가 다른 공고는 "조건이 아쉬운 공고"가 아니라 애초에 상관없는
		// 공고라, 여기에도 올리지 않는다.
		List<ScoredScholarship> ineligibleRanked = scored.stream()
				.filter(s -> !s.eligible())
				.filter(s -> eligibilityGate.belongsTo(s.scholarship(), s.conditions(), matchProfile))
				.filter(s -> matchesFilters(s, filters, scrappedIds))
				.sorted(recommendationComparator()).toList();
		List<ScholarshipCard> ineligible = ineligibleRanked.stream()
				.map(s -> s.toCard(SECTION_INELIGIBLE, scrappedIds, posters))
				.toList();

		Page<ScholarshipCard> paged = slice(others, page, size);

		return new CuratedScholarshipResponse(
				CuratedViewMode.PERSONALIZED,
				ScholarshipRanker.RANKER_VERSION,
				featured.stream().map(s -> s.toCard(SECTION_FEATURED, scrappedIds, posters)).toList(),
				eligibleList.size(),
				calculateProfileCompletionRate(profile),
				campus,
				ineligible,
				paged.items(),
				paged.pagination()
		);
	}

	private Comparator<ScoredScholarship> recommendationComparator() {
		return Comparator.comparingInt(ScoredScholarship::matchScore).reversed()
				.thenComparing(ScoredScholarship::dDay,
						Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(s -> s.scholarship().getId());
	}

	private boolean matchesFilters(ScoredScholarship scored, CuratedFilters filters,
			Set<Long> scrappedIds) {
		Scholarship scholarship = scored.scholarship();
		if (filters.scholarshipType() != null
				&& scholarship.getScholarshipType() != filters.scholarshipType()) {
			return false;
		}
		if (filters.deadline() == DeadlineFilter.HAS_DEADLINE
				&& scholarship.getApplicationEndAt() == null) {
			return false;
		}
		if (filters.deadline() == DeadlineFilter.ALWAYS_OPEN
				&& scholarship.getRecruitmentStatus() != RecruitmentStatus.ALWAYS_OPEN) {
			return false;
		}
		if (filters.deadlineWithinDays() != null
				&& (scored.dDay() == null || scored.dDay() < 0
				|| scored.dDay() > filters.deadlineWithinDays())) {
			return false;
		}
		if (filters.minAmount() != null
				&& (scholarship.getAmount() == null || scholarship.getAmount() < filters.minAmount())) {
			return false;
		}
		if (filters.maxAmount() != null
				&& (scholarship.getAmount() == null || scholarship.getAmount() > filters.maxAmount())) {
			return false;
		}
		return !filters.scrappedOnly() || scrappedIds.contains(scholarship.getId());
	}

	/** 정렬 드롭다운을 비교자로 바꾼다. 마감일이 없는 공고는 어느 기준에서든 뒤로 민다. */
	private Comparator<Scholarship> comparatorFor(CuratedSort sort) {
		if (sort == CuratedSort.LATEST) {
			return Comparator.comparing(Scholarship::getCreatedAt,
							Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(Scholarship::getId, Comparator.reverseOrder());
		}
		return Comparator.comparing(Scholarship::getApplicationEndAt,
						Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(Scholarship::getId);
	}

	/**
	 * 목록을 페이지 하나로 자른다.
	 *
	 * <p>후보가 수백 건 규모라 DB 페이징 대신 메모리에서 자른다. 추천 점수는 DB 가 모르는 값이라
	 * 어차피 전량을 읽어 정렬해야 하고, 비로그인 목록만 따로 DB 페이징을 두면 같은 화면에
	 * 페이징 방식이 두 개가 된다. 후보가 크게 늘면 그때 함께 옮기는 편이 낫다.
	 */
	private <T> Page<T> slice(List<T> all, int page, int size) {
		int safePage = Math.max(page, 1);
		int safeSize = Math.max(size, 1);
		int fromIndex = Math.min((safePage - 1) * safeSize, all.size());
		int toIndex = Math.min(fromIndex + safeSize, all.size());
		int totalPages = (int) Math.ceil((double) all.size() / safeSize);
		return new Page<>(all.subList(fromIndex, toIndex),
				new Pagination(safePage, safeSize, all.size(), totalPages));
	}

	private record Page<T>(List<T> items, Pagination pagination) {
	}

	/** 카드에 실을 포스터 주소. 이미지가 여러 장이면 가장 먼저 붙은 것을 쓴다. */
	private Map<Long, String> findPosterUrls(List<Long> scholarshipIds) {
		if (scholarshipIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, String> result = new HashMap<>();
		imageRepository.findAllByEntityTypeAndEntityIdIn(
						ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarshipIds)
				.forEach(image -> result.putIfAbsent(
						image.getEntityId(), imageStorageService.publicUrl(image.getS3Key())));
		return result;
	}

	/**
	 * 교내 장학금이 사용자 소속 학교의 것인지 판단한다.
	 *
	 * <p>Scholarship 엔티티에 학교 FK 가 없어 수집기가 넣은 {@code provider}(대학명)와
	 * 프로필의 학교명을 대조한다. 문자열 비교라 표기가 다르면 놓칠 수 있으므로,
	 * 추후 {@code school_id} FK 를 추가하는 것이 근본 해법이다.
	 */
	private boolean isSameSchool(Scholarship scholarship, UserProfile profile) {
		if (profile == null || profile.getSchool() == null) {
			return false;
		}
		// 학교가 지정돼 있으면 id 로 끝낸다. 문자열 대조는 표기가 다르면 빗나간다.
		if (scholarship.getSchool() != null && scholarship.getSchool().getId() != null) {
			return scholarship.getSchool().getId().equals(profile.getSchool().getId());
		}
		String schoolName = profile.getSchool().getName();
		String provider = scholarship.getProvider();
		if (schoolName == null || provider == null) {
			return false;
		}
		String normalizedProvider = normalizeSchoolName(provider);
		String normalizedSchool = normalizeSchoolName(schoolName);
		return normalizedProvider.contains(normalizedSchool) || normalizedSchool.contains(normalizedProvider);
	}

	/** 표기 차이(공백, "대학교"/"대") 를 흡수한다. */
	private String normalizeSchoolName(String name) {
		return name.replaceAll("\\s+", "").replaceAll("대학교$", "대");
	}

	/** 로그인 사용자가 스크랩한 장학금 ID 집합. 비로그인/후보 없음이면 빈 집합. */
	private Set<Long> findScrappedIds(UUID userId, List<Long> scholarshipIds) {
		if (userId == null || scholarshipIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(scrapRepository.findScrappedScholarshipIds(userId, scholarshipIds));
	}

	@Transactional(readOnly = true)
	public HomeSummaryResponse getHomeSummary(UUID userId) {
		// 큐레이팅 목록과 같은 기준으로 세야 한다. 관문을 빠뜨리면 홈의 "새 맞춤 장학금 N건" 이
		// 목록에 없는 공고까지 세어 숫자가 어긋난다.
		MatchProfile summaryProfile = matchProfileOf(userId);
		List<ScoredScholarship> eligibleList = scoreOpenScholarships(summaryProfile).stream()
				.filter(ScoredScholarship::eligible)
				.filter(s -> eligibilityGate.belongsTo(s.scholarship(), s.conditions(), summaryProfile))
				.toList();

		LocalDateTime newSince = LocalDateTime.now().minusDays(NEW_MATCHED_DAYS);
		long newMatchedCount = eligibleList.stream()
				.filter(s -> s.scholarship().getCreatedAt() != null && s.scholarship().getCreatedAt().isAfter(newSince))
				.count();
		long urgentDeadlineCount = eligibleList.stream()
				.filter(s -> s.dDay() != null && s.dDay() >= 0 && s.dDay() <= DEADLINE_SOON_DAYS)
				.count();
		long writingApplicationCount =
				essayRepository.countByUser_IdAndStatus(userId, EssayStatus.NOT_STARTED)
						+ essayRepository.countByUser_IdAndStatus(userId, EssayStatus.IN_PROGRESS);
		long newInsightCount = insightRepository.countByCreatedAtAfter(newSince);

		// 인사말용 이름. 회원이 없으면 이름 없이라도 카드 4칸은 그려져야 하므로 예외로 막지 않는다.
		String userName = userRepository.findById(userId).map(User::getName).orElse(null);

		return new HomeSummaryResponse(userName, newMatchedCount, urgentDeadlineCount,
				writingApplicationCount, newInsightCount, newMatchedCount > 0);
	}

	/**
	 * 주어진 후보 중 사용자가 지원 가능한(불충족 조건이 하나도 없는) 장학금 id 집합.
	 *
	 * <p>달력처럼 후보를 밖에서 정해 오는 화면에서 쓴다. {@link #scoreOpenScholarships} 는 OPEN 만
	 * 대상으로 잡아 아직 모집 시작 전(UPCOMING)인 공고가 빠지는데, 달력은 그걸 보여줘야 하므로
	 * 후보를 인자로 받는 형태가 따로 필요하다.
	 */
	@Transactional(readOnly = true)
	public Set<Long> filterEligibleIds(UUID userId, List<Scholarship> candidates) {
		if (candidates.isEmpty()) {
			return Set.of();
		}
		MatchProfile matchProfile = matchProfileOf(userId);
		Map<Long, List<ScholarshipCondition>> conditionsByScholarshipId =
				scholarshipConditionRepository.findAllByScholarshipIn(candidates).stream()
						.collect(Collectors.groupingBy(condition -> condition.getScholarship().getId()));

		return candidates.stream()
				.filter(scholarship -> {
					List<ScholarshipCondition> conditions =
							conditionsByScholarshipId.getOrDefault(scholarship.getId(), List.of());
					// 달력도 목록과 같은 기준으로 걸러야 한다. 관문을 빠뜨리면 목록에는 없는
					// 다른 학교·다른 지역 공고가 달력에만 남는다.
					return score(scholarship, conditions, matchProfile).eligible()
							&& eligibilityGate.belongsTo(scholarship, conditions, matchProfile);
				})
				.map(Scholarship::getId)
				.collect(Collectors.toSet());
	}

	/** 상세 화면 등 다른 서비스에서 재사용: 특정 장학금에 대한 매칭 사유 목록. */
	@Transactional(readOnly = true)
	public List<String> getMatchReasons(UUID userId, Scholarship scholarship,
			List<ScholarshipCondition> conditions) {
		return score(scholarship, conditions, matchProfileOf(userId)).matchReasons();
	}

	private List<ScoredScholarship> scoreOpenScholarships(MatchProfile matchProfile) {
		List<Scholarship> openScholarships =
				scholarshipRepository.findAllOpenForRecommendation(VISIBLE_STATUSES, LocalDateTime.now());
		if (openScholarships.isEmpty()) {
			return List.of();
		}
		Map<Long, List<ScholarshipCondition>> conditionsByScholarshipId =
				scholarshipConditionRepository.findAllByScholarshipIn(openScholarships).stream()
						.collect(Collectors.groupingBy(condition -> condition.getScholarship().getId()));
		return openScholarships.stream()
				.map(scholarship -> score(scholarship,
						conditionsByScholarshipId.getOrDefault(scholarship.getId(), List.of()), matchProfile))
				.toList();
	}

	/**
	 * 조건 하나하나의 판정. 상세 화면이 "왜 이 장학금이 되고 안 되는지" 를 보여주는 데 쓴다.
	 *
	 * <p>충족만 내려주면 사용자는 탈락 이유를 알 수 없고, 판정 불가를 불충족처럼 보여주면
	 * 자격이 있는데도 포기하게 만든다. 그래서 세 값을 그대로 내린다.
	 */
	@Transactional(readOnly = true)
	public List<ConditionJudgement> judgeConditions(UUID userId, List<ScholarshipCondition> conditions) {
		if (conditions.isEmpty()) {
			return List.of();
		}
		// 통합 공고는 판정하지 않는다. 조건이 서로 다른 장학금 것이라, "시각디자인전공 불충족" 같은
		// 표시는 지원 가능한 학생을 돌려보낸다. 목록에서는 보이는데 상세에 엑스가 잔뜩이면
		// 앞뒤도 안 맞는다.
		boolean judge = conditions.stream()
				.noneMatch(c -> c.getScholarship() != null && c.getScholarship().isCombined());
		MatchProfile matchProfile = matchProfileOf(userId);
		return conditions.stream()
				.map(condition -> {
					Evaluation evaluation = judge
							? ConditionMatcher.evaluate(condition, matchProfile)
							: new Evaluation(Result.UNKNOWN, null);
					return new ConditionJudgement(
							condition.getConditionType(), condition.getNecessity(),
							condition.getValueString(), evaluation.result(), evaluation.description());
				})
				.toList();
	}

	/** 조건 1건의 판정 결과. 상세 화면용이라 DTO 로 옮기기 쉬운 값만 담는다. */
	public record ConditionJudgement(ConditionType conditionType, ConditionNecessity necessity,
			String requirement, Result result, String description) {
	}

	/** 프로필과 매칭에 쓰는 사용자 값들을 한 번에 모은다. 없으면 모든 참조 대조가 판정 불가로 넘어간다. */
	private MatchProfile matchProfileOf(UUID userId) {
		if (userId == null) {
			return MatchProfile.of(null);
		}
		return matchProfileOf(userId, userProfileRepository.findByUserId(userId).orElse(null));
	}

	private MatchProfile matchProfileOf(UUID userId, UserProfile profile) {
		if (profile == null) {
			return MatchProfile.of(null);
		}
		return MatchProfile.of(profile,
				userFamilyTypeRepository.findAllByUserProfile_User_Id(userId),
				userInterestRepository.findAllByUserProfile_User_Id(userId));
	}

	/**
	 * 조건 판정을 점수와 지원 가능 여부로 바꾼다.
	 *
	 * <p>게이트는 <b>필수 조건의 불충족</b>만이다. 우대사항은 안 맞아도 지원할 수 있으므로
	 * 탈락시키지 않고 순위만 낮춘다. 이 구분이 없으면 조건을 성실히 채울수록 추천이 비어간다 —
	 * 공고문에는 자격요건만큼 우대사항이 많다.
	 *
	 * <p>부수 효과로 점수가 다시 변별력을 갖는다. 예전에는 지원 가능한 공고면 불충족이 0 이라
	 * {@code matchCount / evaluableCount} 가 항상 1.0 이었고, 결국 나올 수 있는 점수가
	 * 네 가지뿐이었다. 이제 우대사항 불충족이 이 비율에 반영된다.
	 */
	private ScoredScholarship score(Scholarship scholarship, List<ScholarshipCondition> conditions,
			MatchProfile matchProfile) {
		// school_id가 지정된 경우, 사용자 학교와 일치하지 않으면 부적격 처리
		UserProfile profile = matchProfile.profile();
		if (scholarship.getSchoolId() != null && profile != null && profile.getSchool() != null) {
			if (!scholarship.getSchoolId().equals(profile.getSchool().getId())) {
				// 타대학 장학금이므로 지원 불가
				Long dDay = CuratedScholarshipResponse.calculateDday(scholarship.getApplicationEndAt());
				return new ScoredScholarship(scholarship, false, 0, dDay, List.of("다른 학교 장학금"));
			}
		}

		List<Evaluation> evaluations = conditions.stream()
				.map(condition -> ConditionMatcher.evaluate(condition, matchProfile))
				.toList();
		long matchCount = evaluations.stream().filter(e -> e.result() == Result.MATCH).count();
		long mismatchCount = evaluations.stream().filter(e -> e.result() == Result.MISMATCH).count();
		long evaluableCount = matchCount + mismatchCount;

		// 여러 장학금이 한 공고에 실린 경우, 조건은 서로 다른 장학금의 것이 섞여 있다.
		// 그대로 AND 로 걸면 아무도 통과하지 못해 공고가 목록에서 사라진다(실측: 조건 11개가
		// 뭉쳐 시각디자인전공이면서 선교사 자녀인 학생만 지원 가능해졌다).
		boolean eligible = true;
		if (!scholarship.isCombined()) {
			for (int i = 0; i < conditions.size(); i++) {
				if (evaluations.get(i).result() == Result.MISMATCH
						&& conditions.get(i).getNecessity() == ConditionNecessity.REQUIRED) {
					eligible = false;
					break;
				}
			}
		}
		Long dDay = CuratedScholarshipResponse.calculateDday(scholarship.getApplicationEndAt());
		// 순서 매기기는 랭커가 한다. 자격 판정(위)과 나눠 둬야 순서를 바꿀 때 자격 규칙을
		// 건드리지 않는다. 점수 구성도 함께 받아 추천 이유에 그대로 쓴다.
		ScholarshipRanker.Score scored = ScholarshipRanker.score(
				scholarship, conditions, matchCount, evaluableCount, matchProfile.interestIds(), dDay);
		int score = scored.total();

		List<String> reasons = evaluations.stream()
				.filter(e -> e.result() == Result.MATCH && e.description() != null)
				.map(Evaluation::description)
				.toList();
		// 조건 대조에서 나온 사유가 우선이고, 점수 구성은 뒤에 붙인다. 사유가 하나도 없을 때
		// "왜 추천됐는지" 가 빈칸으로 남지 않게 한다.
		if (!reasons.isEmpty() || eligible) {
			List<String> merged = new java.util.ArrayList<>(reasons);
			scored.reasons().stream().filter(reason -> !merged.contains(reason)).forEach(merged::add);
			reasons = List.copyOf(merged);
		}
		if (reasons.isEmpty() && !eligible) {
			reasons = evaluations.stream()
					.filter(e -> e.result() == Result.MISMATCH && e.description() != null)
					.map(Evaluation::description)
					.toList();
		}
		return new ScoredScholarship(scholarship, eligible, score, dDay, reasons, conditions);
	}

	/** 매칭에 쓰이는 프로필 필드(9개) 중 채워진 비율(%). 프로필 없으면 0. */
	private int calculateProfileCompletionRate(UserProfile profile) {
		if (profile == null) {
			return 0;
		}
		Object[] fields = {
				profile.getSchool(), profile.getMajor(), profile.getRegion(), profile.getBirthDate(),
				profile.getGender(), profile.getEnrollmentStatus(), profile.getGrade(),
				profile.getCumulativeGpa() != null ? profile.getCumulativeGpa() : profile.getSemesterGpa(),
				profile.getIncomeLevel()
		};
		long filled = Stream.of(fields).filter(java.util.Objects::nonNull).count();
		return (int) Math.round(100.0 * filled / fields.length);
	}

	private record ScoredScholarship(Scholarship scholarship, boolean eligible, int matchScore, Long dDay,
			List<String> matchReasons, List<ScholarshipCondition> conditions) {

		ScholarshipCard toCard(String section, Set<Long> scrappedIds, Map<Long, String> posterUrls) {
			return ScholarshipCard.of(section, scholarship, posterUrls.get(scholarship.getId()), matchScore,
					matchReasons, eligible, scrappedIds.contains(scholarship.getId()));
		}
	}
}
