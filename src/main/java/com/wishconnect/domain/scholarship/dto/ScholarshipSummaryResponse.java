package com.wishconnect.domain.scholarship.dto;

import java.util.List;

/*
ScholarshipSearchResponse DTO의 results 부분을 담고 있습니다.
여러 정보를 담고 있어 여러 API에서 쓸 수 있게끔 따로 분리해서 만들었습니다.
***/


public record ScholarshipSummaryResponse(
        Long scholarshipId,
        String title,
        String organization,
        String applicationPeriod,
        String maxAmount,
        String deadline,
        int dDay,
        String recruitStatus,
        List<String> tags,
        boolean isScrapped
) {
}
