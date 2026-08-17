package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.common.service.RegionResolver;
import com.wishconnect.domain.scholarship.entity.ConditionRef;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.user.entity.FamilyCategory;
import com.wishconnect.domain.user.entity.FamilyType;
import com.wishconnect.domain.user.entity.Interest;
import com.wishconnect.domain.user.repository.FamilyTypeRepository;
import com.wishconnect.domain.user.repository.InterestRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 라벨 → 마스터 참조 해석.
 *
 * <p>핵심은 <b>해석하지 못한 라벨을 버린다</b>는 것이다. 억지로 가장 가까운 값에 맞추면
 * 자격 있는 학생이 조용히 탈락하는데, 사용자도 우리도 알아챌 방법이 없다.
 */
@ExtendWith(MockitoExtension.class)
class ConditionRefResolverTest {

	@Mock
	private RegionResolver regionResolver;
	@Mock
	private FamilyTypeRepository familyTypeRepository;
	@Mock
	private InterestRepository interestRepository;

	@InjectMocks
	private ConditionRefResolver resolver;

	private Region region(long id, String name) {
		Region region = Region.builder().name(name).build();
		ReflectionTestUtils.setField(region, "id", id);
		return region;
	}

	private FamilyType familyType(long id, String name, FamilyCategory category) {
		FamilyType type = FamilyType.builder().name(name).category(category).build();
		ReflectionTestUtils.setField(type, "id", id);
		return type;
	}

	@Test
	@DisplayName("지역명은 거주지역 해석기를 그대로 쓴다 — 가입 화면과 같은 규칙")
	void resolvesRegionThroughSharedResolver() {
		given(regionResolver.byName("대구광역시 서구")).willReturn(region(42L, "서구"));

		Set<ConditionRef> refs = resolver.resolve(
				ConditionType.REGION_RESIDENCY, List.of("대구광역시 서구"));

		assertThat(refs).containsExactly(ConditionRef.ofId(42L));
	}

	@Test
	@DisplayName("특정할 수 없는 지역명은 버린다 — '중구'는 6개 시도에 있어 임의로 고르면 안 된다")
	void dropsAmbiguousRegion() {
		given(regionResolver.byName("중구")).willReturn(null);

		assertThat(resolver.resolve(ConditionType.REGION_RESIDENCY, List.of("중구"))).isEmpty();
	}

	@Test
	@DisplayName("OR 로 묶인 자격은 참조를 모두 담는다 — 판정은 교집합으로 한다")
	void keepsEveryQualificationInTheSet() {
		given(familyTypeRepository.findAllByNameIn(List.of("기초생활수급자")))
				.willReturn(List.of(familyType(1L, "기초생활수급자", FamilyCategory.FAMILY)));
		given(familyTypeRepository.findAllByNameIn(List.of("차상위 계층")))
				.willReturn(List.of(familyType(4L, "차상위 계층", FamilyCategory.FAMILY)));

		Set<ConditionRef> refs = resolver.resolve(ConditionType.SPECIFIC_QUALIFICATION,
				List.of("기초생활수급자", "차상위 계층"));

		assertThat(refs).containsExactly(ConditionRef.ofId(1L), ConditionRef.ofId(4L));
	}

	@Test
	@DisplayName("마스터에 없는 자격은 버리고 나머지는 살린다")
	void dropsOnlyTheUnknownLabel() {
		given(familyTypeRepository.findAllByNameIn(List.of("기초생활수급자")))
				.willReturn(List.of(familyType(1L, "기초생활수급자", FamilyCategory.FAMILY)));
		given(familyTypeRepository.findAllByNameIn(List.of("가계 곤란"))).willReturn(List.of());

		Set<ConditionRef> refs = resolver.resolve(ConditionType.SPECIFIC_QUALIFICATION,
				List.of("기초생활수급자", "가계 곤란"));

		assertThat(refs).containsExactly(ConditionRef.ofId(1L));
	}

	@Test
	@DisplayName("전공 계열은 enum 이라 ID 가 없어 코드로 넣는다 — 한글 라벨도 받는다")
	void resolvesMajorCategoryAsCode() {
		assertThat(resolver.resolve(ConditionType.MAJOR_FIELD, List.of("공학계열")))
				.containsExactly(ConditionRef.ofCode("ENGINEERING"));
		assertThat(resolver.resolve(ConditionType.MAJOR_FIELD, List.of("ENGINEERING")))
				.containsExactly(ConditionRef.ofCode("ENGINEERING"));
	}

	@Test
	@DisplayName("관심분야는 지원 성격이라 마스터에서 찾아 붙인다")
	void resolvesInterest() {
		given(interestRepository.findFirstByName("생활비 지원"))
				.willReturn(Optional.of(interest(7L, "생활비 지원")));

		assertThat(resolver.resolve(ConditionType.FINANCIAL_AID_TYPE, List.of("생활비 지원")))
				.containsExactly(ConditionRef.ofId(7L));
	}

	@Test
	@DisplayName("대조할 마스터가 없는 유형은 라벨이 와도 참조를 만들지 않는다")
	void ignoresTypesWithoutMaster() {
		assertThat(resolver.resolve(ConditionType.ACADEMIC_CRITERIA, List.of("평점 3.5"))).isEmpty();
		assertThat(resolver.resolve(ConditionType.INCOME_CRITERIA, List.of("3분위"))).isEmpty();
		assertThat(resolver.resolve(ConditionType.UNIVERSITY_TYPE, List.of("4년제"))).isEmpty();
	}

	@Test
	@DisplayName("라벨이 없으면 빈 집합 — 참조 없는 조건은 판정 불가로 넘어간다")
	void emptyLabelsYieldEmptyRefs() {
		assertThat(resolver.resolve(ConditionType.REGION_RESIDENCY, null)).isEmpty();
		assertThat(resolver.resolve(ConditionType.REGION_RESIDENCY, List.of())).isEmpty();
		assertThat(resolver.resolve(ConditionType.REGION_RESIDENCY, List.of("  "))).isEmpty();
	}

	private Interest interest(long id, String name) {
		Interest interest = Interest.builder().name(name).build();
		ReflectionTestUtils.setField(interest, "id", id);
		return interest;
	}
}
