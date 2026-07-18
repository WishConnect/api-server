package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse;
import com.wishconnect.domain.scholarship.dto.HomeSummaryResponse;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.util.ConditionMatcher;
import com.wishconnect.domain.scholarship.util.ConditionMatcher.Evaluation;
import com.wishconnect.domain.scholarship.util.ConditionMatcher.Result;
import com.wishconnect.domain.user.entity.UserProfile;
import com.wishconnect.domain.user.repository.UserProfileRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
프로필 룰 기반 장학금 맞춤 추천(Phase 1)입니다.
- 조건별 판정은 충족/불충족/판정불가 3값(ConditionMatcher). 판정불가는 탈락 사유로 쓰지 않는다.
- 불충족 조건이 하나라도 있으면 추천 목록에서 제외(하드 필터).
- 점수 = 충족 비율(최대 70) + 마감 임박 가점(최대 20) + 판정 가능 조건 존재 가점(10).
- 프로필이 없거나 비어 있으면 전체 OPEN 목록을 마감 임박순으로 반환(온보딩 전 폴백).
추후 Phase 2 에서 스크랩 등 행동 데이터 가점을 이 점수에 결합한다.
 */
@Service
@RequiredArgsConstructor
public class ScholarshipRecommendationService {

	private static final int DEADLINE_SOON_DAYS = 7;

	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final UserProfileRepository userProfileRepository;

	@Transactional(readOnly = true)
	public List<CuratedScholarshipResponse> getCuratedScholarships(UUID userId) {
		UserProfile profile = userProfileRepository.findByUserId(userId).orElse(null);
		List<Scholarship> openScholarships =
				scholarshipRepository.findAllByRecruitmentStatusAndActiveTrueAndDeletedAtIsNull(RecruitmentStatus.OPEN);
		if (openScholarships.isEmpty()) {
			return List.of();
		}
		Map<Long, List<ScholarshipCondition>> conditionsByScholarshipId =
				scholarshipConditionRepository.findAllByScholarshipIn(openScholarships).stream()
						.collect(Collectors.groupingBy(condition -> condition.getScholarship().getId()));

		return openScholarships.stream()
				.map(scholarship -> score(scholarship,
						conditionsByScholarshipId.getOrDefault(scholarship.getId(), List.of()), profile))
				.filter(ScoredScholarship::eligible)
				.sorted(Comparator.comparingInt(ScoredScholarship::matchScore).reversed()
						.thenComparing(scored -> scored.scholarship().getApplicationEndAt(),
								Comparator.nullsLast(Comparator.naturalOrder())))
				.map(scored -> CuratedScholarshipResponse.of(
						scored.scholarship(), scored.matchScore(), scored.matchReason()))
				.toList();
	}

	@Transactional(readOnly = true)
	public HomeSummaryResponse getHomeSummary(UUID userId) {
		UserProfile profile = userProfileRepository.findByUserId(userId).orElse(null);
		List<Scholarship> openScholarships =
				scholarshipRepository.findAllByRecruitmentStatusAndActiveTrueAndDeletedAtIsNull(RecruitmentStatus.OPEN);
		if (openScholarships.isEmpty()) {
			return new HomeSummaryResponse(0, 0);
		}
		Map<Long, List<ScholarshipCondition>> conditionsByScholarshipId =
				scholarshipConditionRepository.findAllByScholarshipIn(openScholarships).stream()
						.collect(Collectors.groupingBy(condition -> condition.getScholarship().getId()));

		long matchedCount = openScholarships.stream()
				.filter(scholarship -> score(scholarship,
						conditionsByScholarshipId.getOrDefault(scholarship.getId(), List.of()), profile).eligible())
				.count();
		long deadlineSoonCount = openScholarships.stream()
				.filter(this::isDeadlineSoon)
				.count();
		return new HomeSummaryResponse(matchedCount, deadlineSoonCount);
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
		int score = 0;
		if (evaluableCount > 0) {
			score += (int) Math.round(70.0 * matchCount / evaluableCount) + 10;
		}
		if (isDeadlineSoon(scholarship)) {
			score += 20;
		}
		score = Math.min(score, 100);

		String reason = evaluations.stream()
				.filter(e -> e.result() == Result.MATCH && e.description() != null)
				.map(Evaluation::description)
				.collect(Collectors.joining(", "));
		if (reason.isBlank()) {
			reason = profile == null
					? "프로필 등록 전이라 전체 공고를 마감 임박순으로 보여드려요"
					: "판정 가능한 조건 정보가 부족한 공고예요";
		}
		return new ScoredScholarship(scholarship, eligible, score, reason);
	}

	private boolean isDeadlineSoon(Scholarship scholarship) {
		if (scholarship.getApplicationEndAt() == null) {
			return false;
		}
		long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), scholarship.getApplicationEndAt().toLocalDate());
		return daysLeft >= 0 && daysLeft <= DEADLINE_SOON_DAYS;
	}

	private record ScoredScholarship(Scholarship scholarship, boolean eligible, int matchScore, String matchReason) {
	}
}
