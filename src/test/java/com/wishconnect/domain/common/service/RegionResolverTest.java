package com.wishconnect.domain.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.common.repository.RegionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 거주지역 이름 해석 검증.
 *
 * <p>시군구를 넣으면서 '중구'가 6개 시도에 존재하게 됐다. 이름만으로 한 건을 특정하려 들면
 * 조회가 여러 건이 되어 500 이 나므로, 그 경계를 테스트로 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class RegionResolverTest {

	@Mock
	private RegionRepository regionRepository;

	@InjectMocks
	private RegionResolver regionResolver;

	private Region seoul;
	private Region gwangjin;

	@BeforeEach
	void setUp() {
		seoul = Region.builder().name("서울").build();
		gwangjin = Region.builder().name("광진구").parent(seoul).build();
		lenient().when(regionRepository.findByNameAndParent_Name(any(), any())).thenReturn(Optional.empty());
		lenient().when(regionRepository.findByNameAndParentIsNull(any())).thenReturn(Optional.empty());
		lenient().when(regionRepository.findAllByName(any())).thenReturn(List.of());
	}

	@DisplayName("시도 이름만 오면 시도를 돌려준다")
	@Test
	void resolvesSido() {
		given(regionRepository.findByNameAndParentIsNull("서울")).willReturn(Optional.of(seoul));

		assertThat(regionResolver.byName("서울")).isEqualTo(seoul);
	}

	@DisplayName("정식 명칭도 마스터 표기로 맞춰 찾는다")
	@Test
	void normalizesOfficialSidoName() {
		given(regionRepository.findByNameAndParentIsNull("서울")).willReturn(Optional.of(seoul));

		assertThat(regionResolver.byName("서울특별시")).isEqualTo(seoul);
	}

	@DisplayName("'서울 광진구' 처럼 시도와 함께 오면 그 조합으로 찾는다")
	@Test
	void resolvesSidoAndSigungu() {
		given(regionRepository.findByNameAndParent_Name("광진구", "서울")).willReturn(Optional.of(gwangjin));

		assertThat(regionResolver.byName("서울 광진구")).isEqualTo(gwangjin);
	}

	@DisplayName("'서울특별시 광진구' 처럼 정식 명칭이 앞에 붙어도 찾는다")
	@Test
	void resolvesOfficialSidoAndSigungu() {
		given(regionRepository.findByNameAndParent_Name("광진구", "서울")).willReturn(Optional.of(gwangjin));

		assertThat(regionResolver.byName("서울특별시 광진구")).isEqualTo(gwangjin);
	}

	@DisplayName("시군구 이름이 전국에서 유일하면 단독으로도 찾는다")
	@Test
	void resolvesUniqueSigunguAlone() {
		given(regionRepository.findAllByName("광진구")).willReturn(List.of(gwangjin));

		assertThat(regionResolver.byName("광진구")).isEqualTo(gwangjin);
	}

	@DisplayName("'중구' 처럼 여러 시도에 있는 이름은 특정하지 않고 null 을 준다")
	@Test
	void doesNotGuessAmbiguousSigungu() {
		Region busan = Region.builder().name("부산").build();
		given(regionRepository.findAllByName("중구")).willReturn(List.of(
				Region.builder().name("중구").parent(seoul).build(),
				Region.builder().name("중구").parent(busan).build()));

		// 임의로 하나를 고르면 엉뚱한 지역으로 저장된다. 호출측이 400 을 내도록 null 을 준다.
		assertThat(regionResolver.byName("중구")).isNull();
	}

	@DisplayName("빈 값이나 모르는 이름은 null")
	@Test
	void returnsNullForUnknown() {
		assertThat(regionResolver.byName(null)).isNull();
		assertThat(regionResolver.byName("  ")).isNull();
		assertThat(regionResolver.byName("없는지역")).isNull();
	}
}
