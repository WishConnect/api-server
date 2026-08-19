package com.wishconnect.domain.insight.controller;

import com.wishconnect.domain.insight.dto.InsightResponse;
import com.wishconnect.domain.insight.service.InsightService;
import com.wishconnect.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "인사이트", description = "장학·지원서 관련 콘텐츠 조회")
@RestController
@RequestMapping("/api/v1/insights")
@RequiredArgsConstructor
public class InsightController {

    private final InsightService insightService;

    @GetMapping
    @Operation(summary = "인사이트 목록 조회", description = "카테고리·출처·태그·키워드로 필터링하고 최신순 또는 지정한 정렬로 페이징합니다.")
    public ApiResponse<InsightResponse> getInsights(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        InsightResponse response = insightService.getInsights(
                category, source, sort, tag, keyword, page, size
        );
        return ApiResponse.ok(response);
    }
}
