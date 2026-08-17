package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.common.repository.RegionRepository;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.user.entity.FamilyCategory;
import com.wishconnect.domain.user.entity.FamilyType;
import com.wishconnect.domain.user.repository.FamilyTypeRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 공공데이터 원문 → 마스터 라벨 후보.
 *
 * <p>고정하려는 성질은 하나다. <b>확실하지 않으면 아무것도 내지 않는다.</b>
 * 라벨이 없으면 그 조건은 판정 불가로 남아 아무도 배제하지 않지만, 잘못된 라벨은
 * 자격 있는 학생을 조용히 탈락시킨다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConditionLabelExtractorTest {

	@Mock
	private RegionRepository regionRepository;
	@Mock
	private FamilyTypeRepository familyTypeRepository;

	@InjectMocks
	private ConditionLabelExtractor extractor;

	private Region seoul;
	private Region daegu;
	private Region jeonnam;
	private Region incheon;

	@BeforeEach
	void setUpMasters() {
		seoul = sido(1L, "서울");
		daegu = sido(2L, "대구");
		jeonnam = sido(3L, "전남");
		incheon = sido(4L, "인천");

		given(regionRepository.findAll()).willReturn(List.of(
				seoul, daegu, jeonnam, incheon,
				sigungu(11L, "광진구", seoul),
				sigungu(12L, "중구", seoul),
				sigungu(21L, "중구", daegu),
				sigungu(22L, "서구", daegu),
				sigungu(31L, "나주시", jeonnam),
				sigungu(41L, "서구", incheon)));

		given(familyTypeRepository.findAll()).willReturn(List.of(
				familyType(1L, "기초생활수급자"),
				familyType(2L, "차상위 계층"),
				familyType(3L, "한부모가정")));
	}

	@Test
	@DisplayName("구분자로 이어 붙은 계열을 쪼갠다")
	void splitsEnumeratedMajors() {
		assertThat(extractor.extract(ConditionType.MAJOR_FIELD, "공학계열,자연계열 및 의약계열"))
				.containsExactly("공학계열", "자연계열", "의약계열");
	}

	@Test
	@DisplayName("시군구가 잡히면 그 시도는 따로 내지 않는다 — OR 이라 시도까지 내면 전남 전체가 통과한다")
	void doesNotWidenToParentSido() {
		assertThat(extractor.extract(ConditionType.REGION_RESIDENCY, "전남 나주시에 거주하는 학생"))
				.containsExactly("전남 나주시");
	}

	@Test
	@DisplayName("시도만 있으면 시도를 낸다")
	void keepsSidoWhenNoSigungu() {
		assertThat(extractor.extract(ConditionType.REGION_RESIDENCY, "서울 거주자에 한함"))
				.containsExactly("서울");
	}

	@Test
	@DisplayName("여러 시도에 있는 시군구는 시도가 함께 있을 때만 인정한다")
	void requiresSidoForDuplicatedSigungu() {
		assertThat(extractor.extract(ConditionType.REGION_RESIDENCY, "대구 서구 거주 학생"))
				.containsExactly("대구 서구");
		assertThat(extractor.extract(ConditionType.REGION_RESIDENCY, "서구 거주 학생")).isEmpty();
	}

	@Test
	@DisplayName("자격은 마스터 표기로 낸다 — 원문의 '차상위계층'을 그대로 넘기면 해석기가 못 찾는다")
	void normalizesQualificationToMasterSpelling() {
		assertThat(extractor.extract(ConditionType.SPECIFIC_QUALIFICATION, "기초생활수급자 및 차상위계층"))
				.containsExactly("기초생활수급자", "차상위 계층");
	}

	@Test
	@DisplayName("마스터에 없는 자격은 내지 않는다")
	void ignoresUnknownQualification() {
		assertThat(extractor.extract(ConditionType.SPECIFIC_QUALIFICATION, "가계가 곤란한 학생")).isEmpty();
	}

	@Test
	@DisplayName("대조할 마스터가 없는 유형과 빈 값은 라벨을 내지 않는다")
	void ignoresTypesWithoutMasterAndBlankValues() {
		assertThat(extractor.extract(ConditionType.RESTRICTION, "휴학생 제외")).isEmpty();
		assertThat(extractor.extract(ConditionType.ACADEMIC_CRITERIA, "평점 3.5 이상")).isEmpty();
		assertThat(extractor.extract(ConditionType.MAJOR_FIELD, "  ")).isEmpty();
		assertThat(extractor.extract(ConditionType.MAJOR_FIELD, null)).isEmpty();
	}

	private Region sido(long id, String name) {
		Region region = Region.builder().name(name).build();
		ReflectionTestUtils.setField(region, "id", id);
		return region;
	}

	private Region sigungu(long id, String name, Region parent) {
		Region region = Region.builder().name(name).parent(parent).build();
		ReflectionTestUtils.setField(region, "id", id);
		return region;
	}

	private FamilyType familyType(long id, String name) {
		FamilyType type = FamilyType.builder().name(name).category(FamilyCategory.FAMILY).build();
		ReflectionTestUtils.setField(type, "id", id);
		return type;
	}
}
