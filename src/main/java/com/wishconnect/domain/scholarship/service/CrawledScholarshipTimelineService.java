package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.scholarship.dto.ParsedNotice;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipTimeline;
import com.wishconnect.domain.scholarship.entity.TimelineOrigin;
import com.wishconnect.domain.scholarship.repository.ScholarshipTimelineRepository;
import com.wishconnect.domain.scholarship.util.NoticeTimelineParser;
import com.wishconnect.domain.scholarship.util.UnivNoticeLlmParser;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    @Transactional
    public int mergeFromParsed(Scholarship scholarship, ParsedNotice notice, String body, LocalDate referenceDate) {
        List<ScholarshipTimeline> existing =
                repository.findAllByScholarshipIdOrderByDisplayOrderAsc(scholarship.getId());
        if (scholarship.isTimelineLocked() || existing.stream().anyMatch(t -> t.getOrigin() != TimelineOrigin.LLM)) {
            return 0;
        }
        // 불완전한 응답·근거 없는 결과는 이미 검증해 저장한 일정을 지우지 않는다.
        List<ScholarshipTimelineValidator.Item> items = NoticeTimelineParser.resolve(notice, body, referenceDate);
        List<ScholarshipTimeline> retained = new ArrayList<>(existing);
        int nextOrder = existing.stream().mapToInt(ScholarshipTimeline::getDisplayOrder).max().orElse(-1) + 1;
        int added = 0;
        for (var item : items) {
            if (retained.size() >= ScholarshipTimelineValidator.MAX_ITEMS) break;
            if (retained.stream().anyMatch(row -> sameSchedule(row, item))) continue;
            // 결과가 일부만 추출됐거나 같은 단계에 별도 전형이 있을 수 있으므로 기존 행을 지우지 않는다.
            var row = ScholarshipTimeline.builder().scholarship(scholarship)
                    .stageCode(item.stageCode()).title(item.title()).dateType(item.dateType())
                    .startDate(item.startDate()).endDate(item.endDate()).dateText(item.dateText())
                    .note(item.note()).evidence(item.evidence()).origin(TimelineOrigin.LLM)
                    .displayOrder(nextOrder++).build();
            repository.save(row);
            retained.add(row);
            added++;
        }
        return added;
    }

    private boolean sameSchedule(ScholarshipTimeline row, ScholarshipTimelineValidator.Item item) {
        return row.getStageCode() == item.stageCode() && row.getDateType() == item.dateType()
                && Objects.equals(row.getTitle(), item.title())
                && Objects.equals(row.getStartDate(), item.startDate())
                && Objects.equals(row.getEndDate(), item.endDate())
                && Objects.equals(row.getDateText(), item.dateText());
    }

    /** API에 일정이 없더라도 찾은 공고 본문에서 추출한다. 다른 API 필드는 갱신하지 않는다. */
    @Transactional
    public int enrichFromPage(Scholarship scholarship, Document page) {
        if (scholarship.isTimelineLocked()
                || repository.findAllByScholarshipIdOrderByDisplayOrderAsc(scholarship.getId())
                .stream().anyMatch(t -> t.getOrigin() != TimelineOrigin.LLM)) return 0;
        var extracted = parser.extractBody(page.outerHtml());
        if (extracted.isEmpty()) return 0; // 이미지·첨부파일만 있는 원문은 OCR 없이 날짜를 추측하지 않는다.
        String body = extracted.get().text();
        ParsedNotice notice;
        try {
            notice = parser.readResponse(llmClient.chat(parser.buildRequest(scholarship.getTitle(), body)))
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("[TimelineEnrich] LLM 실패 scholarshipId={} : {}", scholarship.getId(), e.getMessage());
            return 0;
        }
        return notice == null ? 0 : mergeFromParsed(scholarship, notice, body, SelectionScheduleAssembler.today());
    }
}
