package com.wishconnect.domain.insight.controller;

import com.wishconnect.domain.insight.service.InsightCollectService;
import com.wishconnect.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/insights/sync")
@RequiredArgsConstructor
@Profile("!test")
public class InsightSyncController {
    private final InsightCollectService insightCollectService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ApiResponse<Integer> collect(@RequestParam String keyword) {
        int count = insightCollectService.collectByKeyword(keyword);
        return ApiResponse.ok(count);
    }

}