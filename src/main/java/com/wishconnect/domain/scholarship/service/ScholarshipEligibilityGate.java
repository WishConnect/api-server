package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.common.repository.RegionRepository;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.util.ConditionMatcher;
import com.wishconnect.domain.scholarship.util.ConditionMatcher.Result;
import com.wishconnect.domain.scholarship.util.MatchProfile;
import com.wishconnect.domain.user.entity.UserProfile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * <b>나와 상관없는 공고</b>를 추천에서 걷어낸다. 자격 게이트와는 별개로 늘 적용한다.
 *
 * <p>자격 게이트({@code eligible})는 두 군데서 샌다. 조건이 우대(PREFERRED)로 저장돼 있으면
 * 걸지 않고, 통합 공고({@code combined})는 판정 자체를 건너뛴다. 둘 다 그럴 만한 이유가 있는
 * 완화지만 — 공고문에는 자격요건만큼 우대사항이 많고, 여러 장학금이 한 공고에 실리면 조건이
 * 서로 다른 장학금의 것과 섞인다 — <b>사는 곳과 다니는 학교에는 적용하면 안 된다.</b>
 * 그건 공고마다 다른 세부 요건이 아니라 <b>사람에 대한 사실</b>이라, 틀리면 사용자에게는
 * 곧바로 "왜 이게 뜨지"가 된다. 실제로 인천대에 다니지 않는 사용자에게 인천대 장학금이,
 * 서울 사는 사용자에게 울산·목포 장학금이 떴다.
 *
 * <p>판단 근거는 세 가지이고, <b>하나라도 어긋나면 막는다.</b>
 * <ol>
 *   <li>학교 — {@code scholarship.school_id} 가 프로필 학교와 다르면 막는다. 가장 확실한 신호다.</li>
 *   <li>조건 — {@code UNIVERSITY_TYPE}·{@code REGION_RESIDENCY} 조건이 불일치면 막는다.
 *       necessity·combined 와 무관하게 본다.</li>
 *   <li>제목 — 지역 조건이 아예 없는 공고는 제목에서 지역명을 찾아 견준다.</li>
 * </ol>
 *
 * <p><b>판정할 수 없으면 통과시킨다.</b> "관내에 주소를 두고" 처럼 어느 지역인지 알 수 없는 문구나,
 * 프로필에 학교·지역이 없는 사용자까지 막으면 자격 있는 사람을 떨어뜨린다. 모르는 것을 막는 실패는
 * 사용자가 알아챌 방법이 없어서, 잘못 보여주는 실패보다 고치기 어렵다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScholarshipEligibilityGate {

	private final RegionRepository regionRepository;

	/**
	 * 지역 마스터의 이름 색인. {@code "울산"} → 그 지역과 상위 시도의 id 집합.
	 *
	 * <p>마스터는 시딩으로만 바뀌는 정적 데이터라 한 번 읽어 두고 재사용한다. 요청마다 245건을
	 * 다시 읽으면 큐레이팅 한 번에 쿼리가 그만큼 늘어난다.
	 *
	 * <p>여러 지역에 같은 이름이 있으면({@code "서구"} 는 대구·인천·광주·대전·부산에 모두 있다)
	 * <b>색인에서 뺀다.</b> 어느 곳인지 특정할 수 없는 채로 막으면 엉뚱한 사람이 탈락한다.
	 */
	private volatile Map<String, Set<Long>> regionIndex;

	/** 추천 목록에 올려도 되는 공고인지. */
	public boolean belongsTo(Scholarship scholarship, List<ScholarshipCondition> conditions,
			MatchProfile matchProfile) {
		Decision decision = decide(scholarship, conditions, matchProfile);
		return decision.allowed();
	}

	/**
	 * 통과 여부와 <b>막은 이유</b>. 이유가 없으면 통과다.
	 *
	 * <p>이유를 함께 돌려주는 것은 운영을 위해서다. 지금까지 "왜 이게 떴는지"를 되짚을 방법이
	 * 없어 신고가 들어와도 코드를 처음부터 읽어야 했다.
	 */
	public Decision decide(Scholarship scholarship, List<ScholarshipCondition> conditions,
			MatchProfile matchProfile) {
		if (matchProfile == null || matchProfile.profile() == null) {
			return Decision.allow();
		}
		UserProfile profile = matchProfile.profile();

		String schoolBlock = blockedBySchool(scholarship, profile);
		if (schoolBlock != null) {
			return Decision.block(schoolBlock);
		}

		List<ScholarshipCondition> checked = conditions == null ? List.of() : conditions;
		boolean hasRegionCondition = false;
		for (ScholarshipCondition condition : checked) {
			ConditionType type = condition.getConditionType();
			if (type != ConditionType.UNIVERSITY_TYPE && type != ConditionType.REGION_RESIDENCY) {
				continue;
			}
			if (type == ConditionType.REGION_RESIDENCY) {
				hasRegionCondition = true;
			}
			ConditionMatcher.Evaluation evaluation = ConditionMatcher.evaluate(condition, matchProfile);
			if (evaluation.result() == Result.MISMATCH) {
				return Decision.block(evaluation.description() == null
						? "조건 불일치(" + type + ")" : evaluation.description());
			}
		}

		// 지역 조건이 없는 공고가 훨씬 많다. 그 경우에만 제목을 본다.
		if (!hasRegionCondition) {
			String titleBlock = blockedByTitleRegion(scholarship, matchProfile);
			if (titleBlock != null) {
				return Decision.block(titleBlock);
			}
		}
		return Decision.allow();
	}

	/**
	 * 학교가 지정된 공고는 그 학교 학생에게만 보인다.
	 *
	 * <p>{@code school_id} 가 없으면 "학교와 무관"이 아니라 "모른다"이므로 막지 않는다.
	 * 프로필에 학교가 없는 사용자도 마찬가지다 — 온보딩을 건너뛴 것과 해당 없음을 구별할 수 없다.
	 */
	private String blockedBySchool(Scholarship scholarship, UserProfile profile) {
		if (scholarship == null || scholarship.getSchool() == null || profile.getSchool() == null) {
			return null;
		}
		Long noticeSchoolId = scholarship.getSchool().getId();
		Long mySchoolId = profile.getSchool().getId();
		if (noticeSchoolId == null || mySchoolId == null || noticeSchoolId.equals(mySchoolId)) {
			return null;
		}
		return "다른 학교 공고(" + scholarship.getSchool().getName() + ")";
	}

	/**
	 * 제목에 지역이 박힌 공고를 견준다. {@code "울산광역시 인재육성 장학금"}, {@code "목포시 장학회"}.
	 *
	 * <p><b>본문은 보지 않는다.</b> 제목은 짧아서 지역명이 나오면 대상을 한정하는 뜻일 때가 거의
	 * 전부지만, 본문에는 기관 주소·문의처·연혁처럼 대상과 무관한 지역명이 흔하다. 본문까지 훑으면
	 * 전국 대상 공고를 지역 공고로 오인해 자격 있는 학생을 떨어뜨린다.
	 */
	private String blockedByTitleRegion(Scholarship scholarship, MatchProfile matchProfile) {
		if (scholarship == null || !StringUtils.hasText(scholarship.getTitle())) {
			return null;
		}
		Region myRegion = matchProfile.profile().getRegion();
		if (myRegion == null || matchProfile.regionIds().isEmpty()) {
			return null;
		}
		String title = scholarship.getTitle().replaceAll("\\s+", "");

		String matchedName = null;
		Set<Long> matchedIds = null;
		for (Map.Entry<String, Set<Long>> entry : regionIndex().entrySet()) {
			String name = entry.getKey();
			if (!title.contains(name)) {
				continue;
			}
			// 더 긴 이름이 더 구체적이다. "울산" 과 "울주군" 이 함께 걸리면 뒤쪽을 쓴다.
			if (matchedName == null || name.length() > matchedName.length()) {
				matchedName = name;
				matchedIds = entry.getValue();
			}
		}
		if (matchedName == null) {
			return null;
		}
		boolean mine = matchedIds.stream().anyMatch(matchProfile.regionIds()::contains);
		return mine ? null
				: "다른 지역 공고(제목의 " + matchedName + " · 내 지역 " + myRegion.getName() + ")";
	}

	private Map<String, Set<Long>> regionIndex() {
		Map<String, Set<Long>> cached = regionIndex;
		if (cached != null) {
			return cached;
		}
		synchronized (this) {
			if (regionIndex == null) {
				regionIndex = buildRegionIndex();
			}
			return regionIndex;
		}
	}

	private Map<String, Set<Long>> buildRegionIndex() {
		Map<String, Set<Long>> byName = new HashMap<>();
		Set<String> ambiguous = new java.util.HashSet<>();
		for (Region region : regionRepository.findAll()) {
			String name = region.getName();
			if (!StringUtils.hasText(name) || name.length() < 2 || region.getId() == null) {
				continue;
			}
			Set<Long> ids = new java.util.LinkedHashSet<>();
			ids.add(region.getId());
			if (region.getParent() != null && region.getParent().getId() != null) {
				ids.add(region.getParent().getId());
			}
			if (byName.putIfAbsent(name, ids) != null) {
				ambiguous.add(name);
			}
		}
		ambiguous.forEach(byName::remove);
		log.info("[EligibilityGate] 지역 색인 {}건 (이름이 겹쳐 제외 {}건)", byName.size(), ambiguous.size());
		return Map.copyOf(byName);
	}

	/** 통과 여부와 막은 이유. {@code reason} 은 통과일 때 null. */
	public record Decision(boolean allowed, String reason) {

		static Decision allow() {
			return new Decision(true, null);
		}

		static Decision block(String reason) {
			return new Decision(false, reason);
		}
	}
}
