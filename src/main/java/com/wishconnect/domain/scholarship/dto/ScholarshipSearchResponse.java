package com.wishconnect.domain.scholarship.dto;

import java.util.List;

/*
 검색기능 응답 DTO 입니다.
* **/

public record ScholarshipSearchResponse(
        String keyword,
        int totalCount,
        List<ScholarshipSummaryResponse> results,
        PaginationDto pagination
) {
    public record PaginationDto(
            int page,
            int size,
            int totalCount,
            int totalPages
    ) {
    }
}
