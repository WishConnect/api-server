package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/*
 검색기능 응답 DTO 입니다.
* **/

@Schema(description = "장학금 검색 결과와 페이징 정보")
public record ScholarshipSearchResponse(
        @Schema(description = "적용된 검색어. 없으면 null") String keyword,
        @Schema(description = "전체 검색 결과 수") int totalCount,
        @Schema(description = "현재 페이지의 장학금 카드") List<ScholarshipSummaryResponse> results,
        @Schema(description = "1부터 시작하는 페이징 정보") PaginationDto pagination
) {
    public record PaginationDto(
            int page,
            int size,
            int totalCount,
            int totalPages
    ) {
    }
}
