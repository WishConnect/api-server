package com.wishconnect.domain.insight.controller;

import com.wishconnect.domain.insight.service.InsightCollectService;
import com.wishconnect.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "인사이트 - 관리", description = "인사이트 외부 콘텐츠 수집 (ADMIN 전용)")
@RestController
@RequestMapping("/api/v1/insights/sync")
@RequiredArgsConstructor
@Profile("!test")
public class InsightSyncController {
    private final InsightCollectService insightCollectService;
    private final com.wishconnect.global.audit.AdminAuditLogService adminAuditLogService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @Operation(summary = "인사이트 수동 수집", description = "키워드로 외부 콘텐츠를 검색·저장하고 저장한 건수를 반환합니다. ADMIN 전용입니다.")
    public ApiResponse<Integer> collect(
            @org.springframework.security.core.annotation.AuthenticationPrincipal String actorId,
            @RequestParam String keyword) {
        int count = insightCollectService.collectByKeyword(keyword);
        // 외부 검색 API 쿼터를 쓰는 수동 실행이다.
        adminAuditLogService.record(java.util.UUID.fromString(actorId),
                com.wishconnect.global.audit.AdminAction.INSIGHT_SYNC_TRIGGER, null, null,
                "인사이트 수동 수집 · 키워드 '" + keyword + "' · 저장 " + count + "건");
        return ApiResponse.ok(count);
    }

}
