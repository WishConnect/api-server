package com.wishconnect.domain.scholarship.util;

import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 추천 순서를 정한다.
 *
 * <p>자격 판정(되는가/안 되는가)과 순서 매기기(누가 먼저인가)를 나눠 둔다. 지금까지는 한 함수가
 * 둘 다 해서, 순서를 바꾸려면 자격 규칙까지 건드려야 했다.
 *
 * <p>점수는 네 조각으로 나뉜다. 나눠 둬야 <b>왜 위에 있는지</b>를 사용자에게 설명할 수 있고,
 * 어느 조각을 키웠을 때 지표가 움직이는지도 볼 수 있다.
 *
 * <pre>
 *   자격 적합도  0~50   내 조건과 얼마나 맞는가
 *   관심도       0~20   내가 고른 관심 분야와 겹치는가
 *   공고 신뢰도  0~20   이 공고가 얼마나 채워져 있는가
 *   마감 임박    0~10   곧 닫히는가
 * </pre>
 *
 * <p><b>공고 신뢰도</b>가 특히 중요하다. 자격 게이트는 조건을 보고 거르는데, 조건이 하나도 없는
 * 공고는 아무도 못 걸러서 전원 통과한다. 그대로 두면 <b>정보가 부족한 공고일수록 위로 올라온다.</b>
 * 채워진 공고를 올려서 그걸 상쇄한다.
 */
public final class ScholarshipRanker {

	/** 점수식 판. 행동 기록에 함께 남겨, 판을 올린 뒤 지표가 움직였는지 비교한다. */
	public static final String RANKER_VERSION = "v2";

	private static final int MATCH_MAX = 50;
	private static final int INTEREST_MAX = 20;
	private static final int TRUST_MAX = 20;
	private static final int DEADLINE_MAX = 10;

	/** 이 안에 마감하면 임박으로 본다. */
	private static final int DEADLINE_SOON_DAYS = 7;

	/** 한 기관이 연속으로 차지할 수 있는 최대 칸. */
	private static final int SAME_PROVIDER_RUN = 2;

	private ScholarshipRanker() {
	}

	/** 점수와 그 구성. 구성을 남겨야 추천 이유를 지어내지 않고 쓸 수 있다. */
	public record Score(int total, int match, int interest, int trust, int deadline) {

		/** 사용자에게 보여줄 한 줄. 0점짜리 조각은 말하지 않는다. */
		public List<String> reasons() {
			List<String> reasons = new ArrayList<>();
			if (match >= MATCH_MAX * 0.8) {
				reasons.add("지원 자격이 대부분 맞습니다");
			} else if (match > 0) {
				reasons.add("지원 자격이 일부 맞습니다");
			}
			if (interest > 0) {
				reasons.add("관심 분야와 겹칩니다");
			}
			if (deadline > 0) {
				reasons.add("곧 마감합니다");
			}
			if (trust <= TRUST_MAX * 0.3) {
				reasons.add("공고에 정보가 적어 직접 확인이 필요합니다");
			}
			return reasons;
		}
	}

	/**
	 * @param matchedCount    조건 중 내 정보와 맞은 개수
	 * @param evaluableCount  판정할 수 있었던 조건 개수(모름은 제외)
	 * @param interestIds     사용자가 고른 관심 분야의 {@code interest.id}
	 */
	public static Score score(Scholarship scholarship, List<ScholarshipCondition> conditions,
			long matchedCount, long evaluableCount, java.util.Set<Long> interestIds, Long dDay) {
		int match = evaluableCount == 0 ? 0
				: (int) Math.round((double) MATCH_MAX * matchedCount / evaluableCount);
		int interest = interestScore(conditions, interestIds);
		int trust = trustScore(scholarship, conditions);
		int deadline = deadlineScore(dDay);
		int total = Math.min(match + interest + trust + deadline, 100);
		return new Score(total, match, interest, trust, deadline);
	}

	/**
	 * 관심 분야가 공고의 조건과 겹치는가.
	 *
	 * <p>지원 성격({@code FINANCIAL_AID_TYPE})과 전공 계열({@code MAJOR_FIELD})만 본다. 나머지
	 * 유형은 "무엇을 주는가" 가 아니라 "누가 받을 수 있는가" 라서 취향과 무관하다.
	 */
	private static int interestScore(List<ScholarshipCondition> conditions, java.util.Set<Long> interestIds) {
		if (interestIds == null || interestIds.isEmpty()) {
			return 0;
		}
		boolean hit = conditions.stream()
				.filter(condition -> condition.getConditionType() == ConditionType.FINANCIAL_AID_TYPE
						|| condition.getConditionType() == ConditionType.MAJOR_FIELD)
				.anyMatch(condition -> condition.getRefs().stream()
						.map(ref -> ref.getRefId())
						.filter(java.util.Objects::nonNull)
						.anyMatch(interestIds::contains));
		return hit ? INTEREST_MAX : 0;
	}

	/**
	 * 이 공고를 얼마나 믿을 수 있는가.
	 *
	 * <p>파싱이 실패한 공고를 벌주려는 게 아니라, <b>사용자가 판단할 재료가 있는지</b>를 본다.
	 * 조건도 서류도 없는 공고는 눌러 봐야 "첨부파일 참고" 만 나온다.
	 */
	private static int trustScore(Scholarship scholarship, List<ScholarshipCondition> conditions) {
		int score = 0;
		if (!conditions.isEmpty()) {
			score += 8;
		}
		if (scholarship.getApplicationEndAt() != null) {
			score += 6;
		}
		// 자소서·면접은 "필요 없음" 도 정보다. 판단이 끝났다는 뜻이라 값이 있기만 하면 쳐 준다.
		if (scholarship.getEssayRequirement() != null) {
			score += 1;
		}
		if (scholarship.getInterviewRequirement() != null) {
			score += 1;
		}
		if (scholarship.getSubmissionChannel() != null) {
			score += 4;
		}
		return Math.min(score, TRUST_MAX);
	}

	private static int deadlineScore(Long dDay) {
		if (dDay == null || dDay < 0 || dDay > DEADLINE_SOON_DAYS) {
			return 0;
		}
		// 하루 남은 것이 이레 남은 것보다 급하다.
		return (int) Math.round((double) DEADLINE_MAX * (DEADLINE_SOON_DAYS - dDay) / DEADLINE_SOON_DAYS);
	}

	/**
	 * 같은 기관이 연달아 오지 않게 흩는다.
	 *
	 * <p>점수순으로만 두면 한 학교 공고가 화면을 통째로 덮는다. 인천대는 학과마다 근로장학을
	 * 따로 올려서, 상위 열 칸이 전부 인천대인 일이 실제로 생긴다. 뒤로 밀린 것들은 순서를
	 * 유지한 채 다음 자리로 미룬다 — 점수를 바꾸지는 않는다.
	 */
	public static <T> List<T> diversify(List<T> ranked, java.util.function.Function<T, String> providerOf) {
		if (ranked.size() <= SAME_PROVIDER_RUN) {
			return ranked;
		}
		List<T> remaining = new ArrayList<>(ranked);
		List<T> result = new ArrayList<>(ranked.size());
		Map<String, Integer> run = new HashMap<>();
		String previous = null;

		while (!remaining.isEmpty()) {
			int picked = 0;
			for (int i = 0; i < remaining.size(); i++) {
				String provider = providerOf.apply(remaining.get(i));
				boolean sameRun = provider != null && provider.equals(previous)
						&& run.getOrDefault(provider, 0) >= SAME_PROVIDER_RUN;
				if (!sameRun) {
					picked = i;
					break;
				}
			}
			T next = remaining.remove(picked);
			String provider = providerOf.apply(next);
			run.put(provider, provider != null && provider.equals(previous)
					? run.getOrDefault(provider, 0) + 1 : 1);
			previous = provider;
			result.add(next);
		}
		return result;
	}
}
