package com.wishconnect.domain.scholarship.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/*
ScholarshipSearchResponse DTO의 results 부분을 담고 있습니다.
여러 정보를 담고 있어 여러 API에서 쓸 수 있게끔 따로 분리해서 만들었습니다.
***/

@Getter
@Builder
public class ScholarshipSummaryResponse {
    private Long scholarshipId;
    private String title;
    private String organization;
    private String applicationPeriod;
    private String maxAmount;
    private String deadline;
    private int dDay;
    private String recruitStatus;
    private List<String> tags;
    private boolean isScrapped;
}
