package com.wishconnect.domain.scholarship.controller;

import com.wishconnect.domain.scholarship.entity.ScholarshipPipelineRun;
import com.wishconnect.domain.scholarship.service.ScholarshipPipelineReviewService;
import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/scholarship-pipeline")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scholarship.pipeline.review-enabled", havingValue = "true")
public class ScholarshipPipelineReviewController {
    private final ScholarshipPipelineReviewService service;

    public record CompareRequest(@NotEmpty @Size(max = 10) List<@NotNull @Positive Long> rawIds) { }
    public record ReplayRequest(@NotNull @Positive Long rawId,
                                @NotBlank @Size(max = 100_000) String modelResponse) { }

    @Operation(summary = "기록된 응답/fixture로 재현 — 외부 호출·LLM 과금·정제 데이터 변경 없음")
    @PostMapping("/replay")
    public ApiResponse<ScholarshipPipelineRun> replay(@Valid @RequestBody ReplayRequest request) {
        return ApiResponse.ok(service.replayRaw(request.rawId(), request.modelResponse()));
    }

    @Operation(summary = "원본별 기존/신규 검증 비교 — LLM 과금, 운영 정제 데이터 변경 없음")
    @PostMapping("/compare")
    public ApiResponse<List<ScholarshipPipelineRun>> compare(@Valid @RequestBody CompareRequest request) {
        return ApiResponse.ok(service.compareRaw(request.rawIds().stream().distinct().toList()));
    }

    @Operation(summary = "캡처한 API 상세페이지의 비교 — LLM 과금, 운영 정제 데이터 변경 없음")
    @PostMapping("/runs/{id}/compare")
    public ApiResponse<ScholarshipPipelineRun> compareCaptured(@PathVariable UUID id) {
        return ApiResponse.ok(service.compareCaptured(id));
    }

    @GetMapping("/runs")
    public ApiResponse<List<ScholarshipPipelineRun>> recent() { return ApiResponse.ok(service.recent(20)); }

    @GetMapping("/runs/{id}")
    public ApiResponse<ScholarshipPipelineRun> get(@PathVariable UUID id) { return ApiResponse.ok(service.get(id)); }
}
