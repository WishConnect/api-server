package com.wishconnect.domain.insight.service;

import com.wishconnect.domain.insight.entity.Insight;
import com.wishconnect.domain.insight.entity.InsightSource;
import com.wishconnect.domain.insight.repository.InsightRepository;
import com.wishconnect.global.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsightServiceTest {

    @Mock
    private InsightRepository insightRepository;

    @InjectMocks
    private InsightService insightService;

    @Nested
    @DisplayName("category 검증")
    class CategoryValidation {

        @Test
        @DisplayName("존재하지 않는 category 값이면 예외를 던진다")
        void 잘못된_카테고리는_예외() {
            assertThatThrownBy(() ->
                    insightService.getInsights("INVALID_CATEGORY", null, "latest", null, null, 1, 10)
            ).isInstanceOf(CustomException.class);
        }

        @Test
        @DisplayName("category=ALL이면 필터 없이 조회한다")
        void ALL_카테고리는_필터_없음() {
            when(insightRepository.findAllWithFilter(isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            insightService.getInsights("ALL", null, "latest", null, null, 1, 10);

            verify(insightRepository).findAllWithFilter(isNull(), isNull(), any(Pageable.class));
        }

        @Test
        @DisplayName("category를 지정 안 하면(null) 필터 없이 조회한다")
        void category_null이면_필터_없음() {
            when(insightRepository.findAllWithFilter(isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            insightService.getInsights(null, null, "latest", null, null, 1, 10);

            verify(insightRepository).findAllWithFilter(isNull(), isNull(), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("source 검증")
    class SourceValidation {

        @Test
        @DisplayName("존재하지 않는 source 값이면 예외를 던진다")
        void 잘못된_소스는_예외() {
            assertThatThrownBy(() ->
                    insightService.getInsights(null, "INVALID_SOURCE", "latest", null, null, 1, 10)
            ).isInstanceOf(CustomException.class);
        }

        @Test
        @DisplayName("유효한 source 값이면 해당 enum으로 필터링한다")
        void 유효한_소스는_필터링() {
            when(insightRepository.findAllWithFilter(isNull(), eq(InsightSource.TISTORY), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            insightService.getInsights(null, "TISTORY", "latest", null, null, 1, 10);

            verify(insightRepository).findAllWithFilter(isNull(), eq(InsightSource.TISTORY), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("조회 분기 (tag/keyword 우선순위)")
    class QueryBranching {

        @Test
        @DisplayName("tag가 있으면 findAllByTag를 호출한다")
        void tag가_있으면_태그_조회() {
            when(insightRepository.findAllByTag(eq("자기소개서"), isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            insightService.getInsights(null, null, "latest", "자기소개서", null, 1, 10);

            verify(insightRepository).findAllByTag(eq("자기소개서"), isNull(), isNull(), any(Pageable.class));
        }

        @Test
        @DisplayName("tag는 없고 keyword만 있으면 searchWithFilter를 호출한다")
        void keyword만_있으면_키워드_검색() {
            when(insightRepository.searchWithFilter(isNull(), isNull(), eq("국가장학금"), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            insightService.getInsights(null, null, "latest", null, "국가장학금", 1, 10);

            verify(insightRepository).searchWithFilter(isNull(), isNull(), eq("국가장학금"), any(Pageable.class));
        }

        @Test
        @DisplayName("tag와 keyword 둘 다 없으면 기본 목록 조회를 호출한다")
        void 둘_다_없으면_기본_조회() {
            when(insightRepository.findAllWithFilter(isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            insightService.getInsights(null, null, "latest", null, null, 1, 10);

            verify(insightRepository).findAllWithFilter(isNull(), isNull(), any(Pageable.class));
        }

        @Test
        @DisplayName("tag와 keyword가 둘 다 있으면 tag가 우선한다")
        void tag와_keyword_동시에_있으면_tag_우선() {
            when(insightRepository.findAllByTag(eq("자기소개서"), isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            insightService.getInsights(null, null, "latest", "자기소개서", "국가장학금", 1, 10);

            verify(insightRepository).findAllByTag(eq("자기소개서"), isNull(), isNull(), any(Pageable.class));
            verify(insightRepository, org.mockito.Mockito.never())
                    .searchWithFilter(any(), any(), anyString(), any(Pageable.class));
        }
    }
}