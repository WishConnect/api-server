package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.common.entity.School;
import com.wishconnect.domain.common.repository.RegionRepository;
import com.wishconnect.domain.scholarship.entity.ConditionNecessity;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.util.MatchProfile;
import com.wishconnect.domain.user.entity.UserProfile;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * "나와 상관없는 공고" 관문 검증.
 *
 * <p>사용자가 신고한 두 증상을 그대로 테스트로 옮겼다 — 인천대에 다니지 않는데 인천대 장학금이,
 * 서울에 사는데 울산·목포 장학금이 추천됐다. 자격 게이트가 우대사항·통합 공고를 봐주는 사이로
 * 새어 나가던 것이라, <b>봐주기가 여기까지 오면 안 된다</b>는 것을 함께 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScholarshipEligibilityGateTest {

	@Mock private RegionRepository regionRepository;

	private ScholarshipEligibilityGate gate;

	private Region seoul;
	private Region gwangjin;
	private Region ulsan;
	private Region mokpo;

	@BeforeEach
	void setUp() {
		seoul = region(1L, "서울", null);
		gwangjin = region(2L, "광진구", seoul);
		ulsan = region(3L, "울산", null);
		Region jeonnam = region(4L, "전남", null);
		mokpo = region(5L, "목포시", jeonnam);
		// 이름이 겹치는 지역. 색인에서 빠져야 한다.
		Region daeguSeogu = region(6L, "서구", region(7L, "대구", null));
		Region incheonSeogu = region(8L, "서구", region(9L, "인천", null));

		given(regionRepository.findAll()).willReturn(
				List.of(seoul, gwangjin, ulsan, jeonnam, mokpo, daeguSeogu, incheonSeogu));
		gate = new ScholarshipEligibilityGate(regionRepository);
	}

	// --- 학교 ---

	@Nested
	@DisplayName("학교")
	class SchoolGate {

		@Test
		@DisplayName("다른 학교 공고는 막는다 — 인천대에 다니지 않는데 인천대 장학금이 뜨던 문제")
		void blocksOtherSchool() {
			Scholarship notice = scholarshipOfSchool(school(100L, "인천대학교"));
			MatchProfile me = profileWith(school(200L, "건국대학교"), null);

			var decision = gate.decide(notice, List.of(), me);

			assertThat(decision.allowed()).isFalse();
			assertThat(decision.reason()).contains("인천대학교");
		}

		@Test
		@DisplayName("같은 학교면 통과한다")
		void allowsSameSchool() {
			School inu = school(100L, "인천대학교");
			assertThat(gate.belongsTo(scholarshipOfSchool(inu), List.of(), profileWith(inu, null))).isTrue();
		}

		@Test
		@DisplayName("공고에 학교가 없으면(모른다) 막지 않는다 — 재단·기업 공고가 대부분 여기다")
		void allowsWhenNoticeSchoolUnknown() {
			Scholarship notice = Scholarship.builder().title("한국장학재단 국가장학금").build();
			assertThat(gate.belongsTo(notice, List.of(), profileWith(school(200L, "건국대학교"), null))).isTrue();
		}

		@Test
		@DisplayName("프로필에 학교가 없으면 판단하지 않고 통과시킨다")
		void allowsWhenProfileSchoolMissing() {
			Scholarship notice = scholarshipOfSchool(school(100L, "인천대학교"));
			assertThat(gate.belongsTo(notice, List.of(), profileWith(null, null))).isTrue();
		}
	}

	// --- 지역 ---

	@Nested
	@DisplayName("지역")
	class RegionGate {

		@Test
		@DisplayName("조건이 다른 지역을 가리키면 막는다")
		void blocksByCondition() {
			ScholarshipCondition condition = condition(ConditionType.REGION_RESIDENCY,
					"주민등록상 주소가 울산이며", ConditionNecessity.REQUIRED);

			var decision = gate.decide(scholarship("○○ 장학금"), List.of(condition),
					profileWith(null, gwangjin));

			assertThat(decision.allowed()).isFalse();
		}

		@Test
		@DisplayName("우대사항(PREFERRED)으로 저장돼 있어도 막는다 — 자격 게이트의 봐주기가 여기까지 오면 안 된다")
		void blocksEvenWhenPreferred() {
			ScholarshipCondition condition = condition(ConditionType.REGION_RESIDENCY,
					"주민등록상 주소가 울산이며", ConditionNecessity.PREFERRED);

			assertThat(gate.belongsTo(scholarship("○○ 장학금"), List.of(condition),
					profileWith(null, gwangjin))).isFalse();
		}

		@Test
		@DisplayName("제목에 다른 지역이 박혀 있으면 막는다 — 조건이 아예 없는 공고가 새던 경로")
		void blocksByTitle() {
			var decision = gate.decide(scholarship("2026학년도 목포시 인재육성 장학생 모집"), List.of(),
					profileWith(null, gwangjin));

			assertThat(decision.allowed()).isFalse();
			assertThat(decision.reason()).contains("목포시");
		}

		@Test
		@DisplayName("제목의 지역이 내 상위 시도면 통과한다 — 서울 사는 사람에게 서울 공고")
		void allowsParentSido() {
			assertThat(gate.belongsTo(scholarship("서울 청년 장학금"), List.of(),
					profileWith(null, gwangjin))).isTrue();
		}

		@Test
		@DisplayName("이름이 여러 지역에 겹치면 판단하지 않는다 — '서구'는 대구·인천 양쪽에 있다")
		void ignoresAmbiguousRegionName() {
			assertThat(gate.belongsTo(scholarship("서구 장학회 장학생 모집"), List.of(),
					profileWith(null, gwangjin))).isTrue();
		}

		@Test
		@DisplayName("지역 조건이 이미 있으면 제목은 보지 않는다 — 조건이 판정 불가여도 마찬가지")
		void skipsTitleWhenConditionExists() {
			ScholarshipCondition vague = condition(ConditionType.REGION_RESIDENCY,
					"관내에 주소를 두고 1년 이상", ConditionNecessity.REQUIRED);

			assertThat(gate.belongsTo(scholarship("울산 장학금"), List.of(vague),
					profileWith(null, gwangjin))).isTrue();
		}

		@Test
		@DisplayName("프로필에 지역이 없으면 판단하지 않고 통과시킨다")
		void allowsWhenProfileRegionMissing() {
			assertThat(gate.belongsTo(scholarship("울산 장학금"), List.of(), profileWith(null, null)))
					.isTrue();
		}
	}

	// --- Fixture ---

	private MatchProfile profileWith(School school, Region region) {
		UserProfile profile = UserProfile.builder().school(school).region(region).build();
		return MatchProfile.of(profile);
	}

	private static Scholarship scholarship(String title) {
		return Scholarship.builder().title(title).build();
	}

	private static Scholarship scholarshipOfSchool(School school) {
		Scholarship scholarship = Scholarship.builder().title("교내 장학금").build();
		scholarship.assignSchool(school);
		return scholarship;
	}

	private static ScholarshipCondition condition(ConditionType type, String raw,
			ConditionNecessity necessity) {
		return ScholarshipCondition.builder()
				.conditionType(type)
				.operator(ConditionOperator.EQ)
				.valueString(raw)
				.necessity(necessity)
				.autoExtracted(false)
				.build();
	}

	private static School school(Long id, String name) {
		School school = School.builder().name(name).build();
		setField(school, "id", id);
		return school;
	}

	private static Region region(Long id, String name, Region parent) {
		Region region = Region.builder().name(name).parent(parent).build();
		setField(region, "id", id);
		return region;
	}

	private static void setField(Object target, String name, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
