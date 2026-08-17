package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.common.entity.MajorCategory;
import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.common.service.RegionResolver;
import com.wishconnect.domain.scholarship.entity.ConditionRef;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.user.entity.EnrollmentStatus;
import com.wishconnect.domain.user.entity.FamilyType;
import com.wishconnect.domain.user.entity.Interest;
import com.wishconnect.domain.user.repository.FamilyTypeRepository;
import com.wishconnect.domain.user.repository.InterestRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LLM 이 뽑은 <b>라벨</b>을 마스터 참조로 해석한다.
 *
 * <p>LLM 에게 ID 를 고르게 하지 않는 이유가 있다. 지역만 245건이라 프롬프트에 전부 실으면
 * 호출마다 토큰을 태우고, 무엇보다 <b>모델이 검증할 수 없는 숫자를 지어내기</b> 쉽다.
 * 라벨은 본문에 실제로 쓰인 말이라 근거가 있고, ID 로 바꾸는 건 서버가 확실하게 할 수 있다.
 *
 * <p>해석하지 못한 라벨은 <b>버린다.</b> 억지로 가장 가까운 값에 맞추면 자격 있는 학생이
 * 조용히 탈락한다 — 사용자도 우리도 알아챌 방법이 없는 실패다. 참조가 하나도 안 남으면
 * 그 조건은 대조 대상이 없는 상태가 되고, {@code ConditionMatcher} 는 판정 불가로 넘긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConditionRefResolver {

	/** 공공데이터 계열명 → 마스터 6종. 공백·가운뎃점을 지운 형태로 견준다. */
	private static final Map<String, MajorCategory> MAJOR_ALIASES = Map.ofEntries(
			Map.entry("인문계열", MajorCategory.HUMANITIES_SOCIAL),
			Map.entry("사회계열", MajorCategory.HUMANITIES_SOCIAL),
			Map.entry("인문사회계열", MajorCategory.HUMANITIES_SOCIAL),
			Map.entry("상경계열", MajorCategory.HUMANITIES_SOCIAL),
			Map.entry("어문계열", MajorCategory.HUMANITIES_SOCIAL),
			Map.entry("자연계열", MajorCategory.NATURAL_SCIENCE),
			Map.entry("이학계열", MajorCategory.NATURAL_SCIENCE),
			Map.entry("의약계열", MajorCategory.MEDICAL),
			Map.entry("보건계열", MajorCategory.MEDICAL),
			Map.entry("간호계열", MajorCategory.MEDICAL),
			Map.entry("예술계열", MajorCategory.ARTS_AND_SPORTS),
			Map.entry("체육계열", MajorCategory.ARTS_AND_SPORTS),
			Map.entry("예체능계열", MajorCategory.ARTS_AND_SPORTS));

	private final RegionResolver regionResolver;
	private final FamilyTypeRepository familyTypeRepository;
	private final InterestRepository interestRepository;

	/**
	 * 유형에 맞는 마스터에서 라벨들을 찾아 참조 집합으로 바꾼다.
	 *
	 * @return 해석된 참조. 대조할 마스터가 없는 유형이거나 하나도 못 찾으면 빈 집합
	 */
	public Set<ConditionRef> resolve(ConditionType type, List<String> labels) {
		if (labels == null || labels.isEmpty()) {
			return Set.of();
		}
		Set<ConditionRef> resolved = new LinkedHashSet<>();
		for (String raw : labels) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			String label = raw.trim();
			switch (type) {
				case REGION_RESIDENCY -> addIfPresent(resolved, resolveRegion(label), label, type);
				case SPECIFIC_QUALIFICATION -> addIfPresent(resolved, resolveFamilyType(label), label, type);
				case FINANCIAL_AID_TYPE -> addIfPresent(resolved, resolveInterest(label), label, type);
				case MAJOR_FIELD -> addIfPresent(resolved, resolveMajorCategory(label), label, type);
				case RESTRICTION -> addIfPresent(resolved, resolveEnrollmentStatus(label), label, type);
				// 대조할 마스터가 없는 유형(성적·소득·학년·대학구분·추천서)은 수치나 원문으로 판정한다.
				default -> { }
			}
		}
		return resolved;
	}

	private void addIfPresent(Set<ConditionRef> target, ConditionRef ref, String label, ConditionType type) {
		if (ref == null) {
			log.debug("[ConditionRef] 해석 실패로 버림. type={} label={}", type, label);
			return;
		}
		target.add(ref);
	}

	/**
	 * 지역명 → {@code region.id}.
	 *
	 * <p>회원가입 거주지역과 같은 해석기를 쓴다. "서울 광진구" 조합, 시도명, 전국에서 유일한
	 * 시군구까지 처리하고, 특정하지 못하면 null 을 낸다 — "중구"처럼 6개 시도에 있는 이름을
	 * 임의로 하나 고르지 않는다.
	 */
	private ConditionRef resolveRegion(String label) {
		Region region = regionResolver.byName(label);
		return region == null ? null : ConditionRef.ofId(region.getId());
	}

	/** 가정형태·본인해당 라벨 → {@code family_type.id}. 같은 이름이 카테고리별로 있어 전부 담는다. */
	private ConditionRef resolveFamilyType(String label) {
		List<FamilyType> matches = familyTypeRepository.findAllByNameIn(List.of(label));
		return matches.isEmpty() ? null : ConditionRef.ofId(matches.get(0).getId());
	}

	private ConditionRef resolveInterest(String label) {
		return interestRepository.findFirstByName(label)
				.map(Interest::getId)
				.map(ConditionRef::ofId)
				.orElse(null);
	}

	/**
	 * 전공 계열은 enum 이라 ID 가 없다. 이름을 코드로 넣는다.
	 *
	 * <p>마스터는 대학알리미 대계열 6종인데 <b>한국장학재단 공공데이터는 다른 이름을 쓴다</b>
	 * ({@code 자연계열}·{@code 의약계열}·{@code 인문계열}). 표기만 다르고 같은 것을 가리키므로
	 * 별칭으로 이어준다. 이걸 안 하면 공공데이터 쪽 전공 조건이 통째로 해석되지 않는다.
	 *
	 * <p>{@code 교육계열}·{@code 이공계열} 처럼 6종 어디에도 딱 맞지 않는 이름은 <b>버린다.</b>
	 * 이공계열은 자연과 공학에 걸쳐 있어 하나를 고르면 나머지 학생이 탈락한다.
	 */
	private ConditionRef resolveMajorCategory(String label) {
		for (MajorCategory category : MajorCategory.values()) {
			if (category.name().equalsIgnoreCase(label) || category.getLabel().equals(label)) {
				return ConditionRef.ofCode(category.name());
			}
		}
		MajorCategory alias = MAJOR_ALIASES.get(label.replaceAll("[\\s·]", ""));
		return alias == null ? null : ConditionRef.ofCode(alias.name());
	}

	/** 지원 제한 중 재학상태로 판정 가능한 것만 코드화한다(예: "휴학생 제외"). */
	private ConditionRef resolveEnrollmentStatus(String label) {
		for (EnrollmentStatus status : EnrollmentStatus.values()) {
			if (status.name().equalsIgnoreCase(label)) {
				return ConditionRef.ofCode(status.name());
			}
		}
		return null;
	}
}
