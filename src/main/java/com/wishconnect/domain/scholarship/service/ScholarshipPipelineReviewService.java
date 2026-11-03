package com.wishconnect.domain.scholarship.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.config.LlmProperties;
import com.wishconnect.domain.scholarship.dto.PipelineNoticeInput;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipPipelineRun;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipPipelineRunRepository;
import com.wishconnect.domain.scholarship.util.NoticeTimelineParser;
import com.wishconnect.domain.scholarship.util.TemporalCandidateParser;
import com.wishconnect.domain.scholarship.util.UnivNoticeLlmParser;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 수동 비교 전용. 한 번의 모델 응답을 기존/신규 검증기에 넣으며 운영 정제 데이터를 쓰지 않는다. */
@Service
@RequiredArgsConstructor
public class ScholarshipPipelineReviewService {
    private final RawScholarshipRepository rawRepository;
    private final ScholarshipPipelineRunRepository runRepository;
    private final UnivNoticeLlmParser legacyParser;
    private final TemporalCandidateParser candidateParser;
    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final ObjectMapper mapper;

    @Value("${scholarship.pipeline.review-enabled:false}")
    private boolean enabled;

    public List<ScholarshipPipelineRun> compareRaw(List<Long> ids) {
        requireEnabled();
        return inputs(ids).stream().map(this::compare).toList();
    }

    /** 저장된 원문과 기록/fixture 응답을 사용한다. 외부 사이트·LLM 호출이 없다. */
    public ScholarshipPipelineRun replayRaw(Long id, String response) {
        requireEnabled();
        return evaluate(inputs(List.of(id)).get(0), response, "provided-response");
    }

    private List<PipelineNoticeInput> inputs(List<Long> ids) {
        var rows = rawRepository.findPipelineInputs(ids);
        if (rows.size() != ids.stream().distinct().count()) {
            throw new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND);
        }
        // 유효하지 않은 대상이 뒤에 섞여 있어도 앞 대상에서 먼저 과금하지 않는다.
        for (var raw : rows) {
            if (raw.isDismissed() || (raw.getScholarship() != null && raw.getScholarship().isDeleted())) {
                throw new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND);
            }
        }
        return rows.stream().map(raw -> {
            Long scholarshipId = raw.getScholarship() == null ? null : raw.getScholarship().getId();
            LocalDate date = raw.getCrawledAt() == null
                    ? SelectionScheduleAssembler.today() : raw.getCrawledAt().toLocalDate();
            return raw.getRawHtml() != null
                    ? PipelineNoticeInput.html(raw.getId(), scholarshipId, raw.getSource(), raw.getSourceUrl(),
                        null, raw.getRawHtml(), date)
                    : PipelineNoticeInput.api(raw.getId(), scholarshipId, raw.getSource(), raw.getSourceUrl(),
                        raw.getRawJson(), date);
        }).toList();
    }

    /** 기존 API 보완 수집에서 읽은 페이지를 과금 없이 캡처한다. 동일 입력은 중복 저장하지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void capturePage(Scholarship scholarship, Document page, String url) {
        if (!enabled) return;
        PipelineNoticeInput input = PipelineNoticeInput.html(null, scholarship.getId(), "KOSAF_DETAIL", url,
                scholarship.getTitle(), page.outerHtml(), SelectionScheduleAssembler.today());
        String json = json(input);
        String hash = hash(json);
        if (!runRepository.existsByInputHashAndStatus(hash, "CAPTURED")) {
            save(input, hash, json, null, null, "CAPTURED", null);
        }
    }

    public ScholarshipPipelineRun compareCaptured(UUID id) {
        requireEnabled();
        var run = get(id);
        try {
            return compare(mapper.readValue(run.getInputJson(), PipelineNoticeInput.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("비교 입력을 읽을 수 없습니다.", e);
        }
    }

    public List<ScholarshipPipelineRun> recent(int limit) {
        requireEnabled();
        return runRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.min(Math.max(1, limit), 50)));
    }

    public ScholarshipPipelineRun get(UUID id) {
        requireEnabled();
        return runRepository.findById(id).orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
    }

    private ScholarshipPipelineRun compare(PipelineNoticeInput input) {
        String inputJson = json(input);
        String hash = hash(inputJson);
        if (input.evidenceText().isBlank()) return save(input, hash, inputJson, null, null, "SKIPPED", "EMPTY_INPUT");
        String response;
        try {
            response = llmClient.chat(legacyParser.buildRequest(input.title(), input.body(), input.attachments()));
        } catch (RuntimeException e) {
            // 예외 원문에는 키·URL 등이 포함될 수 있어 분류만 보관한다.
            return save(input, hash, inputJson, null, null, "LLM_FAILED", e.getClass().getSimpleName());
        }
        return evaluate(input, response, llmProperties.parserModel());
    }

    private ScholarshipPipelineRun evaluate(PipelineNoticeInput input, String response, String modelId) {
        String inputJson = json(input);
        String hash = hash(inputJson);
        var parsed = legacyParser.readResponse(response);
        if (parsed.isEmpty()) {
            return save(input, hash, inputJson, null, null, "INVALID_RESPONSE", "INVALID_JSON", response, modelId);
        }
        var notice = parsed.get();
        var baseline = new LinkedHashMap<String, Object>();
        baseline.put("kind", "LEGACY_VALIDATION_SAME_MODEL_RESPONSE");
        baseline.put("period", legacyParser.resolvePeriod(notice, input.body(), input.referenceDate()).orElse(null));
        baseline.put("timeline", NoticeTimelineParser.resolve(notice, input.body(), input.referenceDate()));
        return save(input, hash, inputJson, json(baseline), json(candidateParser.resolve(input, notice)),
                "COMPARED", null, response, modelId);
    }

    private ScholarshipPipelineRun save(PipelineNoticeInput input, String hash, String inputJson,
            String baseline, String candidate, String status, String error) {
        return save(input, hash, inputJson, baseline, candidate, status, error, null, llmProperties.parserModel());
    }

    private ScholarshipPipelineRun save(PipelineNoticeInput input, String hash, String inputJson,
            String baseline, String candidate, String status, String error, String response, String modelId) {
        return runRepository.save(new ScholarshipPipelineRun(input.rawId(), input.scholarshipId(), input.source(),
                TemporalCandidateParser.VERSION, UnivNoticeLlmParser.PROMPT_VERSION, modelId,
                hash, inputJson, baseline, candidate, status, error, response));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("비교 결과 직렬화 실패", e); }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private void requireEnabled() {
        if (!enabled) throw new IllegalStateException("파이프라인 비교 기능이 비활성화되어 있습니다.");
    }
}
