package com.wishconnect.domain.insight.controller;

import com.wishconnect.domain.insight.dto.InsightResponse;
import com.wishconnect.domain.insight.service.InsightService;
import com.wishconnect.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/insights")
@RequiredArgsConstructor
public class InsightController {

    private final InsightService insightService;

    @GetMapping
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
