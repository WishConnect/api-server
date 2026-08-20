package com.wishconnect.domain.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.wishconnect.domain.common.entity.School;
import com.wishconnect.domain.common.repository.SchoolRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 공고의 학교 표기를 마스터로 해석하는 규칙 검증.
 *
 * <p>가장 중요한 것은 <b>애매하면 해석하지 않는다</b>는 쪽이다. 잘못 지정한 학교는 자격 있는
 * 학생을 조용히 떨어뜨리는데, 사용자도 우리도 알아챌 방법이 없다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchoolResolverTest {

	@Mock private SchoolRepository schoolRepository;
	@InjectMocks private SchoolResolver resolver;

	@Test
	@DisplayName("이름이 정확히 같으면 바로 찾는다")
	void exactMatch() {
		School inu = School.builder().name("인천대학교").build();
		given(schoolRepository.findFirstByName("인천대학교")).willReturn(Optional.of(inu));

		assertThat(resolver.byName("인천대학교")).isSameAs(inu);
	}

	@Test
	@DisplayName("'인천대' 처럼 줄여 쓴 표기도 '인천대학교' 로 해석한다")
	void normalizesSuffix() {
		School inu = School.builder().name("인천대학교").build();
		given(schoolRepository.findFirstByName("인천대")).willReturn(Optional.empty());
		given(schoolRepository.findAll()).willReturn(List.of(inu));

		assertThat(resolver.byName("인천대")).isSameAs(inu);
	}

	@Test
	@DisplayName("'국립' 같은 설립 구분 접두어는 떼고 견준다")
	void stripsFoundationPrefix() {
		School inu = School.builder().name("인천대학교").build();
		given(schoolRepository.findFirstByName("국립인천대학교")).willReturn(Optional.empty());
		given(schoolRepository.findAll()).willReturn(List.of(inu));

		assertThat(resolver.byName("국립인천대학교")).isSameAs(inu);
	}

	@Test
	@DisplayName("여러 학교에 걸리면 특정하지 않고 null — 잘못 지정하는 쪽이 더 나쁘다")
	void nullWhenAmbiguous() {
		given(schoolRepository.findFirstByName("한국대")).willReturn(Optional.empty());
		given(schoolRepository.findAll()).willReturn(List.of(
				School.builder().name("한국대학교").build(),
				School.builder().name("한국대학").build()));

		assertThat(resolver.byName("한국대")).isNull();
	}

	@Test
	@DisplayName("재단·기업처럼 학교가 아닌 기관명은 null")
	void nullWhenNotASchool() {
		given(schoolRepository.findFirstByName("한국장학재단")).willReturn(Optional.empty());
		given(schoolRepository.findAll()).willReturn(List.of(School.builder().name("인천대학교").build()));

		assertThat(resolver.byName("한국장학재단")).isNull();
	}

	@Test
	@DisplayName("빈 값이면 null")
	void nullWhenBlank() {
		assertThat(resolver.byName(null)).isNull();
		assertThat(resolver.byName("  ")).isNull();
	}
}
