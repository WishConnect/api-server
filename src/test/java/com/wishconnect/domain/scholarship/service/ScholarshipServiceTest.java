package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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
		given(scholarshipRepository.searchByKeyword(any(), any(), isNull(), isNull(), any(), any()))
				.willReturn(new PageImpl<>(List.of(scholarship("컴퓨터공학 장학금"))));

		scholarshipService.search(null, "  컴퓨터 공학  ", null, "deadline", false, 1, 10);

		ArgumentCaptor<String> keywordNoSpace = ArgumentCaptor.forClass(String.class);
		verify(scholarshipRepository).searchByKeyword(
				keywordNoSpace.capture(), any(), isNull(), isNull(), any(), any(Pageable.class));

		assertThat(keywordNoSpace.getValue()).isEqualTo("컴퓨터공학");
	}

	@Test
	@DisplayName("relevance 정렬은 관련도 전용 쿼리를 사용한다")
	void search_relevanceUsesRelevanceQuery() {
		given(scholarshipRepository.searchByKeywordOrderByRelevance(any(), any(), isNull(), isNull(), any(), any()))
				.willReturn(new PageImpl<>(List.of(scholarship("성적우수장학금"))));

		scholarshipService.search(null, "성적 우수", null, "relevance", false, 1, 10);

		verify(scholarshipRepository).searchByKeywordOrderByRelevance(
				eq("성적우수"), eq(List.of("성적 우수")), isNull(), isNull(), any(), any(Pageable.class));
	}

	@Test
	@DisplayName("학교 축약어는 대표 학교명 후보를 함께 검색한다")
	void search_expandsUniversityAlias() {
		given(scholarshipRepository.searchByKeyword(any(), any(), isNull(), isNull(), any(), any()))
				.willReturn(new PageImpl<>(List.of(scholarship("건국대학교 장학금"))));

		scholarshipService.search(null, "건대", null, "deadline", false, 1, 10);

		ArgumentCaptor<String> keywordNoSpace = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<List<String>> keywords = ArgumentCaptor.forClass(List.class);
		verify(scholarshipRepository).searchByKeyword(
				keywordNoSpace.capture(), keywords.capture(), isNull(), isNull(), any(), any(Pageable.class));

		assertThat(keywordNoSpace.getValue()).isEqualTo("건국대학교");
		assertThat(keywords.getValue()).contains("건대", "건국대학교");
	}

	@Test
	@DisplayName("유형 키워드와 나머지 검색어를 분리해 타입 조건 AND 텍스트 조건으로 검색한다")
	void search_splitsKeywordTypeFromTextKeyword() {
		given(scholarshipRepository.searchByKeyword(any(), any(), eq(ScholarshipType.WORK_STUDY), isNull(), any(), any()))
				.willReturn(new PageImpl<>(List.of(scholarship("국가근로장학금"))));

		scholarshipService.search(null, "근로 컴퓨터", null, "deadline", false, 1, 10);

		verify(scholarshipRepository).searchByKeyword(
				eq("컴퓨터"), eq(List.of("컴퓨터")), eq(ScholarshipType.WORK_STUDY), isNull(), any(), any(Pageable.class));
	}

	@Test
	@DisplayName("교내 성적 검색은 교내 타입 안에서 성적 키워드로 좁힌다")
	void search_internalKeywordDoesNotReturnAllInternalScholarships() {
		given(scholarshipRepository.searchByKeyword(any(), any(), eq(ScholarshipType.INTERNAL), isNull(), any(), any()))
				.willReturn(new PageImpl<>(List.of(scholarship("성적우수장학금"))));

		scholarshipService.search(null, "교내 성적", null, "deadline", false, 1, 10);

		verify(scholarshipRepository).searchByKeyword(
				eq("성적"), eq(List.of("성적")), eq(ScholarshipType.INTERNAL), isNull(), any(), any(Pageable.class));
	}

	@Test
	@DisplayName("유형 키워드만 검색하면 해당 유형 전체를 조회한다")
	void search_typeKeywordOnlySearchesByType() {
		given(scholarshipRepository.searchByKeyword(isNull(), any(), eq(ScholarshipType.WORK_STUDY), isNull(), any(), any()))
				.willReturn(new PageImpl<>(List.of(scholarship("국가근로장학금"))));

		scholarshipService.search(null, "근로", null, "deadline", false, 1, 10);

		verify(scholarshipRepository).searchByKeyword(
				isNull(), eq(List.of("__wishconnect_no_text_keyword__")), eq(ScholarshipType.WORK_STUDY),
				isNull(), any(), any(Pageable.class));
	}

	@Test
	@DisplayName("유형 글자가 단어 일부이면 타입 조건으로 분리하지 않는다")
	void search_doesNotSplitTypeKeywordInsideNormalWord() {
		given(scholarshipRepository.searchByKeyword(any(), any(), isNull(), isNull(), any(), any()))
				.willReturn(new PageImpl<>(List.of(scholarship("검색 결과"))));

		scholarshipService.search(null, "근로자 자녀 장학금", null, "deadline", false, 1, 10);
		scholarshipService.search(null, "근로장려금 수급자", null, "deadline", false, 1, 10);
		scholarshipService.search(null, "교외활동 우수자", null, "deadline", false, 1, 10);

		ArgumentCaptor<String> keywordNoSpace = ArgumentCaptor.forClass(String.class);
		verify(scholarshipRepository, times(3)).searchByKeyword(
				keywordNoSpace.capture(), any(), isNull(), isNull(), any(), any(Pageable.class));

		assertThat(keywordNoSpace.getAllValues())
				.containsExactly("근로자자녀장학금", "근로장려금수급자", "교외활동우수자");
	}

	@Test
	@DisplayName("키워드 없는 일반 검색도 마감일 가드 기준 시각을 전달한다")
	void search_withoutKeywordPassesDeadlineGuardNow() {
		given(scholarshipRepository.findAllWithoutKeyword(isNull(), any(), any()))
				.willReturn(new PageImpl<>(List.of(scholarship("모집중 장학금"))));

		scholarshipService.search(null, null, null, "deadline", false, 1, 10);

		ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(scholarshipRepository).findAllWithoutKeyword(isNull(), now.capture(), any(Pageable.class));
		assertThat(now.getValue()).isNotNull();
	}

	@Test
	@DisplayName("스크랩 검색도 마감일 가드 기준 시각을 전달한다")
	void search_scrappedOnlyPassesDeadlineGuardNow() {
		UUID userId = UUID.randomUUID();
		given(scholarshipRepository.findScrappedByUser(eq(userId), isNull(), any(), any()))
				.willReturn(new PageImpl<>(List.of(scholarship("스크랩 장학금"))));

		scholarshipService.search(userId, null, null, "deadline", true, 1, 10);

		ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(scholarshipRepository).findScrappedByUser(eq(userId), isNull(), now.capture(), any(Pageable.class));
		assertThat(now.getValue()).isNotNull();
	}

	@Test
	@DisplayName("page는 1 이상, size는 1 이상 100 이하여야 한다")
	void search_rejectsInvalidPagination() {
		assertThatThrownBy(() -> scholarshipService.search(null, "장학금", null, "deadline", false, 0, 10))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

		assertThatThrownBy(() -> scholarshipService.search(null, "장학금", null, "deadline", false, 1, 0))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

		assertThatThrownBy(() -> scholarshipService.search(null, "장학금", null, "deadline", false, 1, 101))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
	}

	@Test
	@DisplayName("검색어 길이와 LIKE 와일드카드 문자는 제한한다")
	void search_rejectsExpensiveOrWildcardKeyword() {
		assertThatThrownBy(() -> scholarshipService.search(null, "가".repeat(101), null, "deadline", false, 1, 10))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

		assertThatThrownBy(() -> scholarshipService.search(null, "%", null, "deadline", false, 1, 10))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

		assertThatThrownBy(() -> scholarshipService.search(null, "_", null, "deadline", false, 1, 10))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

		assertThatThrownBy(() -> scholarshipService.search(null, "\\", null, "deadline", false, 1, 10))
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
