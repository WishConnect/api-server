package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ScholarshipServiceTest {

	@Mock
	private ScholarshipRepository scholarshipRepository;

	@Mock
	private ScrapRepository scrapRepository;

	@InjectMocks
	private ScholarshipService scholarshipService;

	@Test
	@DisplayName("검색어 앞뒤 공백과 중간 띄어쓰기를 정규화해 검색한다")
	void search_normalizesKeywordWhitespace() {
		given(scholarshipRepository.searchByKeyword(any(), any(), isNull(), isNull(), any()))
				.willReturn(new PageImpl<>(List.of(scholarship("컴퓨터공학 장학금"))));

		scholarshipService.search(null, "  컴퓨터 공학  ", null, "deadline", false, 1, 10);

		ArgumentCaptor<String> keywordNoSpace = ArgumentCaptor.forClass(String.class);
		verify(scholarshipRepository).searchByKeyword(
				keywordNoSpace.capture(), any(), isNull(), isNull(), any(Pageable.class));

		assertThat(keywordNoSpace.getValue()).isEqualTo("컴퓨터공학");
	}

	@Test
	@DisplayName("relevance 정렬은 관련도 전용 쿼리를 사용한다")
	void search_relevanceUsesRelevanceQuery() {
		given(scholarshipRepository.searchByKeywordOrderByRelevance(any(), any(), isNull(), isNull(), any()))
				.willReturn(new PageImpl<>(List.of(scholarship("성적우수장학금"))));

		scholarshipService.search(null, "성적 우수", null, "relevance", false, 1, 10);

		verify(scholarshipRepository).searchByKeywordOrderByRelevance(
				eq("성적우수"), eq(List.of("성적 우수")), isNull(), isNull(), any(Pageable.class));
	}

	@Test
	@DisplayName("학교 축약어는 대표 학교명 후보를 함께 검색한다")
	void search_expandsUniversityAlias() {
		given(scholarshipRepository.searchByKeyword(any(), any(), isNull(), isNull(), any()))
				.willReturn(new PageImpl<>(List.of(scholarship("건국대학교 장학금"))));

		scholarshipService.search(null, "건대", null, "deadline", false, 1, 10);

		ArgumentCaptor<String> keywordNoSpace = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<List<String>> keywords = ArgumentCaptor.forClass(List.class);
		verify(scholarshipRepository).searchByKeyword(
				keywordNoSpace.capture(), keywords.capture(), isNull(), isNull(), any(Pageable.class));

		assertThat(keywordNoSpace.getValue()).isEqualTo("건국대학교");
		assertThat(keywords.getValue()).contains("건대", "건국대학교");
	}

	@Test
	@DisplayName("근로 같은 유형 키워드는 장학금 타입 조건으로 함께 검색한다")
	void search_resolvesKeywordType() {
		given(scholarshipRepository.searchByKeyword(any(), any(), eq(ScholarshipType.WORK_STUDY), isNull(), any()))
				.willReturn(new PageImpl<>(List.of(scholarship("국가근로장학금"))));

		scholarshipService.search(null, "국가 근로", null, "deadline", false, 1, 10);

		verify(scholarshipRepository).searchByKeyword(
				eq("국가근로"), any(), eq(ScholarshipType.WORK_STUDY), isNull(), any(Pageable.class));
	}

	@Test
	@DisplayName("page와 size는 1 이상이어야 한다")
	void search_rejectsInvalidPagination() {
		assertThatThrownBy(() -> scholarshipService.search(null, "장학금", null, "deadline", false, 0, 10))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

		assertThatThrownBy(() -> scholarshipService.search(null, "장학금", null, "deadline", false, 1, 0))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
	}

	private Scholarship scholarship(String title) {
		Scholarship scholarship = Scholarship.builder()
				.title(title)
				.provider("한국장학재단")
				.amount(1_000_000L)
				.build();
		ReflectionTestUtils.setField(scholarship, "id", 1L);
		return scholarship;
	}
}
