package com.wishconnect.domain.scholarship.util;

import com.wishconnect.domain.scholarship.dto.ParsedNotice;
import com.wishconnect.domain.scholarship.dto.TimelineItemRequest;
import com.wishconnect.domain.scholarship.entity.TimelineDateType;
import com.wishconnect.domain.scholarship.entity.TimelineStageCode;
import com.wishconnect.domain.scholarship.service.ScholarshipTimelineValidator;
import com.wishconnect.domain.scholarship.service.ScholarshipTimelineValidator.Item;
import com.wishconnect.global.exception.CustomException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** LLM 일정의 날짜·단계·인용을 검증한다. 근거 없는 일정 하나 때문에 전체 공고를 버리지는 않는다. */
public final class NoticeTimelineParser {

    private static final Pattern SOURCE_DATE = Pattern.compile(
            "(?<!\\d)(?:(\\d{4})\\s*[.\\-/년]\\s*)?(\\d{1,2})\\s*[.\\-/월]\\s*(\\d{1,2})(?!\\d)");

    private NoticeTimelineParser() { }

    public static List<Item> resolve(ParsedNotice notice, String body, LocalDate referenceDate) {
        List<Item> result = new ArrayList<>();
        LinkedHashSet<Item> seen = new LinkedHashSet<>();
        if (notice.timeline() == null) return result;
        for (ParsedNotice.Timeline row : notice.timeline()) {
            if (result.size() >= ScholarshipTimelineValidator.MAX_ITEMS) break;
            if (row == null || row.stageCode() == null || row.dateType() == null
                    || !UnivNoticeLlmParser.isEvidenceGrounded(row.evidence(), body)) {
                continue;
            }
            try {
                TimelineStageCode stage = TimelineStageCode.valueOf(row.stageCode());
                // 접수 일정은 기존 모집기간 검증으로 만든다. 다른 연도·트랙의 접수를 덮지 않는다.
                if (stage != stageOf(row.evidence())) {
                    continue;
                }
                TimelineDateType type = TimelineDateType.valueOf(row.dateType());
                if (type == TimelineDateType.RANGE && !TemporalCandidateParser.hasRangeSeparator(row.evidence())) {
                    continue;
                }
                LocalDate start = resolveDate(row.startDate(), row.evidence(), referenceDate);
                LocalDate end = resolveDate(row.endDate(), row.evidence(), referenceDate);
                if ((row.startDate() != null && start == null) || (row.endDate() != null && end == null)) continue;
                if (type == TimelineDateType.TBD && (row.dateText() == null || row.dateText().isBlank()
                        || !isVagueDate(row.dateText(), row.evidence()))) continue;
                // 표시명·비고는 모델이 다시 쓴 문구 대신 표준명·실제 인용만 보낸다.
                String note = UnivNoticeLlmParser.isEvidenceGrounded(row.note(), row.evidence()) ? row.note() : null;
                Item item = ScholarshipTimelineValidator.normalize(List.of(new TimelineItemRequest(
                        stage.name(), stage == TimelineStageCode.CUSTOM ? customTitle(row.evidence()) : null,
                        type.name(), start, end, row.dateText(), note, row.evidence()))).get(0);
                if (seen.add(item)) result.add(item);
            } catch (IllegalArgumentException | CustomException e) {
                // 잘못된 단계·날짜·역순·필드 길이는 이 행만 폐기한다.
            }
        }
        return result;
    }


    private static LocalDate resolveDate(String value, String evidence, LocalDate referenceDate) {
        if (value == null || value.isBlank()) return null;
        try {
            LocalDate claimed = LocalDate.parse(value);
            var tokens = SOURCE_DATE.matcher(TemporalCandidateParser.normalizeDates(evidence).replaceAll("['’‘`](\\d{2})\\s*[.년]",
                    (referenceDate.getYear() / 100) + "$1."));
            LocalDate previous = null;
            LinkedHashSet<LocalDate> matching = new LinkedHashSet<>();
            while (tokens.find()) {
                int month = Integer.parseInt(tokens.group(2)), day = Integer.parseInt(tokens.group(3));
                boolean explicit = tokens.group(1) != null;
                int year = explicit ? Integer.parseInt(tokens.group(1))
                        : previous == null ? referenceDate.getYear() : previous.getYear();
                if (!explicit && previous != null && previous.getMonthValue() == 12 && month == 1) year++;
                LocalDate quoted;
                try { quoted = LocalDate.of(year, month, day); } catch (RuntimeException invalid) { continue; }
                if (!explicit && previous == null) quoted = UnivNoticeLlmParser.withYearNear(quoted, referenceDate);
                previous = quoted;
                if (month == claimed.getMonthValue() && day == claimed.getDayOfMonth()) {
                    if (explicit && !quoted.equals(claimed)) return null;
                    matching.add(quoted);
                }
            }
            return matching.size() == 1 ? matching.iterator().next() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean isVagueDate(String text, String evidence) {
        String compact = text.replaceAll("\\s+", "");
        return evidence.replaceAll("\\s+", "").contains(compact)
                && compact.matches(".*(미정|예정|추후|공지|통보|\\d{1,2}월|\\d{1,2}[.월]?(초|중|말|상순|중순|하순)).*");
    }

    static TimelineStageCode stageOf(String evidence) {
        String text = evidence.replaceAll("\\s+", "");
        if (text.matches(".*(접수기간|신청기간|모집기간|확인서|제출기한|제출기간).*")) return null;
        // 날짜 앞에 붙은 단계명과 뒷부분의 별도 단계명을 구분한다.
        var firstDate = SOURCE_DATE.matcher(text);
        String label = firstDate.find() ? text.substring(0, firstDate.start()) : text;
        String afterLabel = text.substring(label.length());
        if (afterLabel.matches(".*(서류심사|서류평가|면접|합격자발표|장학생발표|장학생선발|장학금지급|장학증서수여식).*")) {
            return null;
        }
        if (label.matches(".*(서류|1차).*(발표|결과|합격).*")) return TimelineStageCode.DOC_RESULT;
        if (label.matches(".*(최종|선발|합격|장학생).*(발표|결과|통보).*")) return TimelineStageCode.FINAL_RESULT;
        if (label.matches(".*(서류심사|서류평가).*")) return TimelineStageCode.DOC_REVIEW;
        if (label.contains("면접")) return TimelineStageCode.INTERVIEW;
        if (label.matches(".*(장학금|지원금).*(지급|입금).*")) return TimelineStageCode.PAYMENT;
        // 발표로 단정할 수 없는 경우에는 공고에서 확인한 단계 이름으로 저장한다.
        return customTitle(label) == null ? null : TimelineStageCode.CUSTOM;
    }

    private static String customTitle(String evidence) {
        String compact = evidence.replaceAll("\\s+", "");
        if (compact.matches(".*장학생선발(?:일정|일)?[:：].*")) return "장학생 선발";
        if (compact.matches(".*장학(?:증서|금)수여식.*")) return "장학증서 수여식";
        return null;
    }
}
