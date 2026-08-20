package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.dto.RegionConditionBackfillResponse;
import com.wishconnect.domain.scholarship.entity.ConditionRef;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.util.ConditionLabelExtractor;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

/**
 * 본문 근거로 거주 요건 조건을 채우는 백필 검증.
 *
 * <p>가장 중요한 것은 <b>제목에 낚이지 않는다</b>는 쪽이다. 제목의 지역명으로 판단하면
 * {@code "서울장학재단 전국 대학생 장학금"} 같은 전국 공고가 서울 밖 사용자에게서 사라진다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegionConditionBackfillServiceTest {

	@Mock private ScholarshipRepository scholarshipRepository;
	@Mock private ScholarshipConditionRepository scholarshipConditionRepository;
	@Mock private ConditionLabelExtractor labelExtractor;
	@Mock private ConditionRefResolver refResolver;

	@InjectMocks private RegionConditionBackfillService service;

	@Test
	@DisplayName("본문에 거주 요건이 있으면 조건을 만들고 근거 문장을 남긴다")
	void createsConditionFromBody() {
		Scholarship target = scholarship("2026학년도 목포시 인재육성 장학생 모집",
				"목포시에 주민등록을 두고 거주하는 대학생을 대상으로 한다.");
		stubTargets(target);
		given(labelExtractor.extract(any(), any())).willReturn(List.of("목포시"));
		given(refResolver.resolve(any(), any())).willReturn(Set.of(refOf(5L)));

		RegionConditionBackfillResponse result = service.backfill(100, false);

		assertThat(result.matched()).isEqualTo(1);
		assertThat(result.filled()).isEqualTo(1);

		ArgumentCaptor<ScholarshipCondition> saved = ArgumentCaptor.forClass(ScholarshipCondition.class);
		verify(scholarshipConditionRepository).save(saved.capture());
		assertThat(saved.getValue().getConditionType()).isEqualTo(ConditionType.REGION_RESIDENCY);
		assertThat(saved.getValue().getValueString()).contains("목포시").contains("거주");
		assertThat(saved.getValue().isAutoExtracted()).isTrue();
	}

	@Test
	@DisplayName("제목에만 지역명이 있으면 아무것도 만들지 않는다 — 전국 공고가 사라지면 안 된다")
	void ignoresTitleOnlyRegion() {
		Scholarship nationwide = scholarship("서울장학재단 전국 대학생 장학금",
				"전국 4년제 대학 재학생이면 누구나 지원할 수 있습니다.");
		stubTargets(nationwide);

		RegionConditionBackfillResponse result = service.backfill(100, false);

		assertThat(result.matched()).isZero();
		assertThat(result.filled()).isZero();
		verify(scholarshipConditionRepository, never()).save(any());
	}

	@Test
	@DisplayName("어느 지역인지 해석하지 못하면 조건을 만들지 않는다 — 판정 불가 조건만 늘어난다")
	void skipsWhenRegionUnresolved() {
		// "충청도" 는 마스터에 없다(충북·충남으로 나뉜다). 근거는 있는데 어디인지 특정이 안 되는 경우다.
		Scholarship vague = scholarship("○○ 장학금", "충청도에 거주하는 자에 한한다.");
		stubTargets(vague);
		given(labelExtractor.extract(any(), any())).willReturn(List.of("충청도"));
		given(refResolver.resolve(any(), any())).willReturn(Set.of());

		RegionConditionBackfillResponse result = service.backfill(100, false);

		assertThat(result.matched()).isEqualTo(1);
		assertThat(result.unresolved()).isEqualTo(1);
		assertThat(result.filled()).isZero();
		verify(scholarshipConditionRepository, never()).save(any());
	}

	@Test
	@DisplayName("dryRun 이면 저장하지 않고 무엇이 채워질지만 돌려준다")
	void dryRunSavesNothing() {
		Scholarship target = scholarship("장학생 모집",
				"울산광역시에 거주하는 학생을 대상으로 선발한다.");
		stubTargets(target);
		given(labelExtractor.extract(any(), any())).willReturn(List.of("울산"));
		given(refResolver.resolve(any(), any())).willReturn(Set.of(refOf(3L)));

		RegionConditionBackfillResponse result = service.backfill(100, true);

		assertThat(result.dryRun()).isTrue();
		assertThat(result.filled()).isEqualTo(1);
		assertThat(result.samples()).hasSize(1);
		assertThat(result.samples().get(0).evidence()).contains("울산");
		verify(scholarshipConditionRepository, never()).save(any());
	}

	// --- Fixture ---

	private void stubTargets(Scholarship... scholarships) {
		given(scholarshipRepository.findWithoutConditionType(any(), any(Pageable.class)))
				.willReturn(List.of(scholarships));
	}

	private static Scholarship scholarship(String title, String description) {
		return Scholarship.builder().title(title).description(description).build();
	}

	private static ConditionRef refOf(Long regionId) {
		return ConditionRef.ofId(regionId);
	}
}
