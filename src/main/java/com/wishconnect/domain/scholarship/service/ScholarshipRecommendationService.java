package com.wishconnect.domain.scholarship.service;

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
- 불충족 조건이 있으면 eligible=false로 "조건 미충족" 분류(목록 하단 노출, 명세의 조건 미충족 장학금).
- 점수 = 충족 비율(최대 70) + 판정 가능 조건 존재 가점(10) + 마감 임박 가점(20).
- featured = 지원 가능 공고 중 마감이 가장 가까운 카드. campus = 교내(INTERNAL).
- 프로필이 없으면 배제 없이 전체 OPEN을 마감 임박순으로 노출(온보딩 전 폴백).
추후 Phase 2 에서 스크랩 등 행동 데이터 가점을 결합한다.
 */
@Service
@RequiredArgsConstructor
public class ScholarshipRecommendationService {

	private static final int DEADLINE_SOON_DAYS = 7;
	private static final int NEW_MATCHED_DAYS = 7;

	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final UserProfileRepository userProfileRepository;

	@Transactional(readOnly = true)
	public CuratedScholarshipResponse getCuratedScholarships(UUID userId, int page, int size) {
		UserProfile profile = userProfileRepository.findByUserId(userId).orElse(null);
		List<ScoredScholarship> scored = scoreOpenScholarships(profile);

		List<ScoredScholarship> eligibleList = scored.stream().filter(ScoredScholarship::eligible).toList();
		ScoredScholarship featured = eligibleList.stream()
				.filter(s -> s.dDay() != null && s.dDay() >= 0)
				.min(Comparator.comparingLong(ScoredScholarship::dDay))
				.orElse(null);

		List<ScholarshipCard> campus = eligibleList.stream()
				.filter(s -> s.scholarship().getScholarshipType() == ScholarshipType.INTERNAL)
				.map(ScoredScholarship::toCard)
				.toList();

		// 그 외 추천: 지원 가능(점수순) 뒤에 조건 미충족(분류 노출)을 붙인다. featured/교내는 제외.
		List<ScholarshipCard> others = Stream.concat(
						eligibleList.stream()
								.filter(s -> s != featured)
								.filter(s -> s.scholarship().getScholarshipType() != ScholarshipType.INTERNAL)
								.sorted(Comparator.comparingInt(ScoredScholarship::matchScore).reversed()
										.thenComparing(s -> s.scholarship().getApplicationEndAt(),
												Comparator.nullsLast(Comparator.naturalOrder()))),
						scored.stream()
								.filter(s -> !s.eligible())
								.sorted(Comparator.comparingInt(ScoredScholarship::matchScore).reversed()))
				.map(ScoredScholarship::toCard)
				.toList();

		int safePage = Math.max(page, 1);
		int safeSize = Math.max(size, 1);
		int fromIndex = Math.min((safePage - 1) * safeSize, others.size());
		int toIndex = Math.min(fromIndex + safeSize, others.size());
		int totalPages = (int) Math.ceil((double) others.size() / safeSize);

		return new CuratedScholarshipResponse(
				featured == null ? null : featured.toCard(),
				calculateProfileCompletionRate(profile),
				campus,
				others.subList(fromIndex, toIndex),
				new Pagination(safePage, safeSize, others.size(), totalPages)
		);
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
		return new HomeSummaryResponse(newMatchedCount, urgentDeadlineCount, newMatchedCount > 0);
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

		ScholarshipCard toCard() {
			return ScholarshipCard.of(scholarship, matchScore, matchReasons, eligible);
		}
	}
}
