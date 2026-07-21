package com.wishconnect.domain.scholarship.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/*
 검색기능 응답 DTO 입니다.
* **/

@Getter
@Builder
public class ScholarshipSearchResponse {
    private String keyword;
    private int totalCount;
    private List<ScholarshipSummaryResponse> results;
    private PaginationDto pagination;

    @Getter
    @Builder
    public static class PaginationDto{
        private int page;
        private int size;
        private int totalCount;
        private int totalPages;
    }
}
