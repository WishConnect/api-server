package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.scholarship.dto.ParsedNotice;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipTimeline;
import com.wishconnect.domain.scholarship.entity.TimelineOrigin;
import com.wishconnect.domain.scholarship.entity.TimelineDateType;
import com.wishconnect.domain.scholarship.entity.TimelineStageCode;
import com.wishconnect.domain.scholarship.util.NoticeAttachmentText;
import com.wishconnect.domain.scholarship.util.TemporalCandidateParser;
import com.wishconnect.domain.scholarship.repository.ScholarshipTimelineRepository;
import com.wishconnect.domain.scholarship.util.NoticeTimelineParser;
import com.wishconnect.domain.scholarship.util.UnivNoticeLlmParser;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 대학 공지 파싱과 공공데이터 상세페이지 크롤링의 선발 일정 저장을 공유한다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CrawledScholarshipTimelineService {

    private final ScholarshipTimelineRepository repository;
    private final UnivNoticeLlmParser parser;
    private final LlmClient llmClient;
    private final TemporalCandidateParser temporalParser;

    @Transactional
    public int mergeFromParsed(Scholarship scholarship, ParsedNotice notice, String body, LocalDate referenceDate) {
        List<ScholarshipTimeline> existing =
                repository.findAllByScholarshipIdOrderByDisplayOrderAsc(scholarship.getId());
        if (scholarship.isTimelineLocked() || existing.stream().anyMatch(t -> t.getOrigin() != TimelineOrigin.LLM)) {
            return 0;
        }
        // 불완전한 응답·근거 없는 결과는 이미 검증해 저장한 일정을 지우지 않는다.
        List<ScholarshipTimelineValidator.Item> items = NoticeTimelineParser.withSourceEvidence(notice, body, referenceDate);
        List<ScholarshipTimeline> retained = new ArrayList<>(existing);
        List<ScholarshipTimelineValidator.Item> replacements = new ArrayList<>();
        for (var row : existing) {
            var split = splitRegionalInterview(row, items);
            if (split.isEmpty()) continue;
            var missing = split.stream().filter(item -> retained.stream().noneMatch(current -> sameSchedule(current, item)))
                    .filter(item -> !replacements.contains(item)).toList();
            if (retained.size() - 1 + replacements.size() + missing.size() > ScholarshipTimelineValidator.MAX_ITEMS) continue;
            repository.delete(row);
            retained.remove(row);
            replacements.addAll(missing);
        }
        // 같은 전형·단계의 새 근거가 하나로 확정되면 이전 날짜를 교체한다.
        // 원문에 여전히 남은 일정과, 어느 전형인지 구분할 수 없는 여러 일정은 보존한다.
        for (var row : List.copyOf(retained)) {
            var updates = items.stream().filter(item -> sameProcess(row, item)).toList();
            long previousCount = retained.stream().filter(previous -> sameProcess(previous, row)).count();
            if (updates.size() != 1 || previousCount != 1 || sameSchedule(row, updates.get(0))
                    || row.getEvidence() == null
                    || UnivNoticeLlmParser.isEvidenceGrounded(row.getEvidence(), body)) continue;
            repository.delete(row);
            retained.remove(row);
        }
        int nextOrder = existing.stream().mapToInt(ScholarshipTimeline::getDisplayOrder).max().orElse(-1) + 1;
        int added = 0;
        for (var item : Stream.concat(replacements.stream(), items.stream()).toList()) {
            if (retained.size() >= ScholarshipTimelineValidator.MAX_ITEMS) break;
            if (retained.stream().anyMatch(row -> sameSchedule(row, item))) continue;
            // 새 결과에 없는 다른 단계는 유지한다.
            var row = ScholarshipTimeline.builder().scholarship(scholarship)
                    .stageCode(item.stageCode()).title(item.title()).dateType(item.dateType())
                    .startDate(item.startDate()).endDate(item.endDate()).dateText(item.dateText())
                    .note(item.note()).evidence(item.evidence()).origin(TimelineOrigin.LLM)
                    .displayOrder(nextOrder++).build();
            repository.save(row);
            retained.add(row);
            added++;
        }
        if (added > 0) restoreSourceOrder(retained, items);
        return added;
    }

    private void restoreSourceOrder(List<ScholarshipTimeline> retained, List<ScholarshipTimelineValidator.Item> items) {
        List<ScholarshipTimeline> ordered = new ArrayList<>();
        for (var item : items) {
            retained.stream().filter(row -> sameSchedule(row, item) && !ordered.contains(row))
                    .findFirst().ifPresent(ordered::add);
        }
        // 일부 일정만 확인할 수 있으면 기존 행의 순서는 유지한다.
        if (ordered.size() != retained.size()) return;
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).changeDisplayOrder(i);
    }

    private boolean sameSchedule(ScholarshipTimeline row, ScholarshipTimelineValidator.Item item) {
        return row.getStageCode() == item.stageCode() && row.getDateType() == item.dateType()
                && Objects.equals(row.getTitle(), item.title())
                && Objects.equals(row.getStartDate(), item.startDate())
                && Objects.equals(row.getEndDate(), item.endDate())
                && Objects.equals(compactDateText(row.getDateText()), compactDateText(item.dateText()))
                && (item.note() == null || Objects.equals(row.getNote(), item.note()));
    }

    private boolean sameProcess(ScholarshipTimeline row, ScholarshipTimelineValidator.Item item) {
        return row.getStageCode() == item.stageCode()
                && Objects.equals(row.getTitle(), item.title())
                && Objects.equals(compactDateText(row.getNote()), compactDateText(item.note()));
    }

    private boolean sameProcess(ScholarshipTimeline first, ScholarshipTimeline second) {
        return first.getStageCode() == second.getStageCode()
                && Objects.equals(first.getTitle(), second.getTitle())
                && Objects.equals(compactDateText(first.getNote()), compactDateText(second.getNote()));
    }

    private static String compactDateText(String text) {
        return text == null ? null : text.replaceAll("[\\s ]+", "");
    }

    private List<ScholarshipTimelineValidator.Item> splitRegionalInterview(ScholarshipTimeline row,
            List<ScholarshipTimelineValidator.Item> items) {
        if (row.getStageCode() != TimelineStageCode.INTERVIEW || row.getDateType() != TimelineDateType.RANGE
                || row.getEvidence() == null || !row.getEvidence().contains("/")) return List.of();
        var split = items.stream().filter(item -> item.stageCode() == TimelineStageCode.INTERVIEW
                && item.dateType() == TimelineDateType.SINGLE && item.note() != null
                && comparableEvidence(row.getEvidence()).equals(comparableEvidence(item.evidence()))).toList();
        return split.size() == 2 && !split.get(0).note().equals(split.get(1).note())
                && Objects.equals(row.getStartDate(), split.get(0).startDate())
                && Objects.equals(row.getEndDate(), split.get(1).startDate()) ? split : List.of();
    }

    private static String comparableEvidence(String value) {
        return value.replaceAll("^[\\s\\d.)•◦-]+", "").replaceAll("[\\s ]+", "");
    }

    /** 공고의 명시 일정과 API 날짜와 일치하는 마감 시각을 보완한다. */
    @Transactional
    public int enrichFromPage(Scholarship scholarship, Document page) {
        return enrichPage(scholarship, page).saved();
    }

    public record EnrichmentOutcome(int saved, boolean complete) { }

    @Transactional
    public EnrichmentOutcome enrichPage(Scholarship scholarship, Document page) {
        if (scholarship.isTimelineLocked()
                || repository.findAllByScholarshipIdOrderByDisplayOrderAsc(scholarship.getId())
                .stream().anyMatch(t -> t.getOrigin() != TimelineOrigin.LLM)) return new EnrichmentOutcome(0, false);
        LocalDate referenceDate = scholarship.getApplicationEndAt() == null ? SelectionScheduleAssembler.today()
                : scholarship.getApplicationEndAt().toLocalDate();
        String html = page.outerHtml();
        String htmlBody = parser.extractBody(html).map(UnivNoticeLlmParser.ExtractedBody::text).orElse("");
        String body = htmlBody;
        if (NoticeTimelineParser.withSourceEvidence(null, body, referenceDate).isEmpty()) {
            String attachment = NoticeAttachmentText.scheduleEvidence(page, referenceDate);
            if (!attachment.isEmpty()) body += "\n" + attachment;
        }
        if (body.isBlank()) return new EnrichmentOutcome(0, false);
        var period = temporalParser.resolveExplicitRecruitmentPeriod(null, body, referenceDate);
        if (period != null && period.end().time() != null) {
            scholarship.refineApiDeadline(period.end().date().atTime(period.end().time()));
        }
        ParsedNotice notice = null;
        // 첨부에서 찾은 명시 일정은 파일 내용을 외부 모델에 다시 보내지 않아도 저장할 수 있다.
        if (!htmlBody.isBlank()) {
            try {
                notice = parser.readResponse(llmClient.chat(parser.buildRequest(scholarship.getTitle(), htmlBody,
                        parser.extractAttachments(html)))).orElse(null);
            } catch (RuntimeException e) {
                log.warn("[TimelineEnrich] LLM 실패 scholarshipId={}: {}", scholarship.getId(), e.getClass().getSimpleName());
            }
        }
        return new EnrichmentOutcome(mergeFromParsed(scholarship, notice, body, referenceDate),
                notice != null || htmlBody.isBlank());
    }
}
