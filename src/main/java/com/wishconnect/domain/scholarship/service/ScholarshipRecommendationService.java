package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.application.entity.EssayStatus;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse;
import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse.Pagination;
import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse.ScholarshipCard;
import com.wishconnect.domain.scholarship.dto.HomeSummaryResponse;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.domain.scholarship.util.ConditionMatcher;
import com.wishconnect.domain.scholarship.util.ConditionMatcher.Evaluation;
import com.wishconnect.domain.scholarship.util.ConditionMatcher.Result;
import com.wishconnect.domain.user.entity.UserProfile;
import com.wishconnect.domain.user.repository.UserProfileRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
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
프로필 룰 기반 장학금 맞춤 추천(Phase 1)입니다.
- 조건별 판정은 충족/불충족/판정불가 3값(ConditionMatcher). 판정불가는 탈락 사유로 쓰지 않는다.
- 불충족 조건이 있으면 eligible=false로 "조건 미충족" 분류(ineligibleScholarships 로 분리 노출).
- 점수 = 충족 비율(최대 70) + 판정 가능 조건 존재 가점(10) + 마감 임박 가점(20).
- featured = 지원 가능 공고 중 마감 임박순 상위 5건(캐러셀). campus = 소속 학교의 교내(INTERNAL).
- 프로필이 없으면 배제 없이 전체 OPEN을 마감 임박순으로 노출(온보딩 전 폴백).
추후 Phase 2 에서 스크랩 등 행동 데이터 가점을 결합한다.
 */
@Service
@RequiredArgsConstructor
public class ScholarshipRecommendationService {

	private static final int DEADLINE_SOON_DAYS = 7;
	/** 히어로 배너(dot 캐러셀) 노출 개수. 피그마 기준 5개. */
	private static final int FEATURED_LIMIT = 5;
	private static final int NEW_MATCHED_DAYS = 7;

	private final ScholarshipRepository scholarshipRepository;
	// 홈 요약의 "작성 중인 지원서" 칸. 도메인은 다르지만 집계 한 줄이라 별도 서비스를 두지 않는다.
	private final EssayRepository essayRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final UserProfileRepository userProfileRepository;
	private final ScrapRepository scrapRepository;

	@Transactional(readOnly = true)
	public CuratedScholarshipResponse getCuratedScholarships(UUID userId, int page, int size) {
		UserProfile profile = userProfileRepository.findByUserId(userId).orElse(null);
		List<ScoredScholarship> scored = scoreOpenScholarships(profile);

		// 화면에 노출되는 카드(featured/교내/그외)의 스크랩 여부를 한 번에 조회한다.
		// 상세·검색과 달리 큐레이팅 카드에 isScrapped 가 없어, 뒤로가기 시 스크랩 상태가 사라지던 문제 해결.
		Set<Long> scrappedIds = findScrappedIds(userId, scored);

		List<ScoredScholarship> eligibleList = scored.stream().filter(ScoredScholarship::eligible).toList();

		// featured: 마감 임박순 상위 N. 피그마가 dot 캐러셀이라 단건이 아니라 목록이다.
		// 근로장학은 추천 성격이 아니라 그 외 목록에서 빠지므로, 히어로 배너에도 올리지 않는다.
		List<ScoredScholarship> featured = eligibleList.stream()
				.filter(s -> s.scholarship().getScholarshipType() != ScholarshipType.WORK_STUDY)
				.filter(s -> s.dDay() != null && s.dDay() >= 0)
				.sorted(Comparator.comparingLong(ScoredScholarship::dDay))
				.limit(FEATURED_LIMIT)
				.toList();
		Set<Long> featuredIds = featured.stream()
				.map(s -> s.scholarship().getId())
				.collect(Collectors.toSet());

		// 교내는 소속 학교 것만 노출한다. 학교 정보가 없으면(온보딩 전) 판단할 수 없어 비운다.
		List<ScholarshipCard> campus = eligibleList.stream()
				.filter(s -> s.scholarship().getScholarshipType() == ScholarshipType.INTERNAL)
				.filter(s -> isSameSchool(s.scholarship(), profile))
				.map(s -> s.toCard(scrappedIds))
				.toList();

		// 그 외 추천: 지원 가능한 교외(EXTERNAL) 공고를 점수순으로. featured 중복은 제외한다.
		List<ScholarshipCard> others = eligibleList.stream()
				.filter(s -> !featuredIds.contains(s.scholarship().getId()))
				.filter(s -> s.scholarship().getScholarshipType() == ScholarshipType.EXTERNAL)
				.sorted(Comparator.comparingInt(ScoredScholarship::matchScore).reversed()
						.thenComparing(s -> s.scholarship().getApplicationEndAt(),
								Comparator.nullsLast(Comparator.naturalOrder())))
				.map(s -> s.toCard(scrappedIds))
				.toList();

		// 조건 미충족은 피그마상 별도 섹션이라 분리한다. 근로장학(WORK_STUDY)은 성격이 달라 제외.
		List<ScholarshipCard> ineligible = scored.stream()
				.filter(s -> !s.eligible())
				.filter(s -> s.scholarship().getScholarshipType() != ScholarshipType.WORK_STUDY)
				.sorted(Comparator.comparingInt(ScoredScholarship::matchScore).reversed())
				.map(s -> s.toCard(scrappedIds))
				.toList();

		int safePage = Math.max(page, 1);
		int safeSize = Math.max(size, 1);
		int fromIndex = Math.min((safePage - 1) * safeSize, others.size());
		int toIndex = Math.min(fromIndex + safeSize, others.size());
		int totalPages = (int) Math.ceil((double) others.size() / safeSize);

		return new CuratedScholarshipResponse(
				featured.stream().map(s -> s.toCard(scrappedIds)).toList(),
				calculateProfileCompletionRate(profile),
				campus,
				others.subList(fromIndex, toIndex),
				ineligible,
				new Pagination(safePage, safeSize, others.size(), totalPages)
		);
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
		String schoolName = profile.getSchool().getName();
		String provider = scholarship.getProvider();
		if (schoolName == null || provider == null) {
			return false;
		}
		return normalizeSchoolName(provider).equals(normalizeSchoolName(schoolName));
	}

	/** 표기 차이(공백, "대학교"/"대") 를 흡수한다. */
	private String normalizeSchoolName(String name) {
		return name.replaceAll("\\s+", "").replaceAll("대학교$", "대");
	}

	/** 로그인 사용자가 스크랩한 장학금 ID 집합. 비로그인/후보 없음이면 빈 집합. */
	private Set<Long> findScrappedIds(UUID userId, List<ScoredScholarship> scored) {
		if (userId == null || scored.isEmpty()) {
			return Set.of();
		}
		List<Long> ids = scored.stream().map(s -> s.scholarship().getId()).toList();
		return new java.util.HashSet<>(scrapRepository.findScrappedScholarshipIds(userId, ids));
	}

	@Transactional(readOnly = true)
	public HomeSummaryResponse getHomeSummary(UUID userId) {
		UserProfile profile = userProfileRepository.findByUserId(userId).orElse(null);
		List<ScoredScholarship> eligibleList = scoreOpenScholarships(profile).stream()
				.filter(ScoredScholarship::eligible)
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
		return new HomeSummaryResponse(
				newMatchedCount, urgentDeadlineCount, writingApplicationCount, newMatchedCount > 0);
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
		UserProfile profile = userProfileRepository.findByUserId(userId).orElse(null);
		Map<Long, List<ScholarshipCondition>> conditionsByScholarshipId =
				scholarshipConditionRepository.findAllByScholarshipIn(candidates).stream()
						.collect(Collectors.groupingBy(condition -> condition.getScholarship().getId()));

		return candidates.stream()
				.filter(scholarship -> score(scholarship,
						conditionsByScholarshipId.getOrDefault(scholarship.getId(), List.of()), profile).eligible())
				.map(Scholarship::getId)
				.collect(Collectors.toSet());
	}

	/** 상세 화면 등 다른 서비스에서 재사용: 특정 장학금에 대한 매칭 사유 목록. */
	@Transactional(readOnly = true)
	public List<String> getMatchReasons(UUID userId, Scholarship scholarship,
			List<ScholarshipCondition> conditions) {
		UserProfile profile = userProfileRepository.findByUserId(userId).orElse(null);
		return score(scholarship, conditions, profile).matchReasons();
	}

	private List<ScoredScholarship> scoreOpenScholarships(UserProfile profile) {
		List<Scholarship> openScholarships =
				scholarshipRepository.findAllOpenForRecommendation(RecruitmentStatus.OPEN, LocalDateTime.now());
		if (openScholarships.isEmpty()) {
			return List.of();
		}
		Map<Long, List<ScholarshipCondition>> conditionsByScholarshipId =
				scholarshipConditionRepository.findAllByScholarshipIn(openScholarships).stream()
						.collect(Collectors.groupingBy(condition -> condition.getScholarship().getId()));
		return openScholarships.stream()
				.map(scholarship -> score(scholarship,
						conditionsByScholarshipId.getOrDefault(scholarship.getId(), List.of()), profile))
				.toList();
	}

	private ScoredScholarship score(Scholarship scholarship, List<ScholarshipCondition> conditions,
			UserProfile profile) {
		List<Evaluation> evaluations = conditions.stream()
				.map(condition -> ConditionMatcher.evaluate(condition, profile))
				.toList();
		long matchCount = evaluations.stream().filter(e -> e.result() == Result.MATCH).count();
		long mismatchCount = evaluations.stream().filter(e -> e.result() == Result.MISMATCH).count();
		long evaluableCount = matchCount + mismatchCount;

		boolean eligible = mismatchCount == 0;
		Long dDay = CuratedScholarshipResponse.calculateDday(scholarship.getApplicationEndAt());
		int score = 0;
		if (evaluableCount > 0) {
			score += (int) Math.round(70.0 * matchCount / evaluableCount) + 10;
		}
		if (dDay != null && dDay >= 0 && dDay <= DEADLINE_SOON_DAYS) {
			score += 20;
		}
		score = Math.min(score, 100);

		List<String> reasons = evaluations.stream()
				.filter(e -> e.result() == Result.MATCH && e.description() != null)
				.map(Evaluation::description)
				.toList();
		if (reasons.isEmpty() && !eligible) {
			reasons = evaluations.stream()
					.filter(e -> e.result() == Result.MISMATCH && e.description() != null)
					.map(Evaluation::description)
					.toList();
		}
		return new ScoredScholarship(scholarship, eligible, score, dDay, reasons);
	}

	/** 매칭에 쓰이는 프로필 필드(9개) 중 채워진 비율(%). 프로필 없으면 0. */
	private int calculateProfileCompletionRate(UserProfile profile) {
		if (profile == null) {
			return 0;
		}
		Object[] fields = {
				profile.getSchool(), profile.getMajor(), profile.getRegion(), profile.getBirthYear(),
				profile.getGender(), profile.getEnrollmentStatus(), profile.getGrade(),
				profile.getCumulativeGpa() != null ? profile.getCumulativeGpa() : profile.getSemesterGpa(),
				profile.getIncomeLevel()
		};
		long filled = Stream.of(fields).filter(java.util.Objects::nonNull).count();
		return (int) Math.round(100.0 * filled / fields.length);
	}

	private record ScoredScholarship(Scholarship scholarship, boolean eligible, int matchScore, Long dDay,
			List<String> matchReasons) {

		ScholarshipCard toCard(Set<Long> scrappedIds) {
			return ScholarshipCard.of(scholarship, matchScore, matchReasons, eligible,
					scrappedIds.contains(scholarship.getId()));
		}
	}
}
