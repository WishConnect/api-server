package com.wishconnect.domain.scholarship.util;

import com.wishconnect.domain.scholarship.dto.ParsedNotice;
import com.wishconnect.domain.scholarship.dto.TimelineItemRequest;
import com.wishconnect.domain.scholarship.dto.TemporalParseResult.Point;
import com.wishconnect.domain.scholarship.entity.TimelineDateType;
import com.wishconnect.domain.scholarship.entity.TimelineStageCode;
import com.wishconnect.domain.scholarship.service.ScholarshipTimelineValidator;
import com.wishconnect.domain.scholarship.service.ScholarshipTimelineValidator.Item;
import com.wishconnect.global.exception.CustomException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** LLM 일정의 날짜·단계·인용을 검증한다. 근거 없는 일정 하나 때문에 전체 공고를 버리지는 않는다. */
public final class NoticeTimelineParser {

    private static final Pattern SOURCE_DATE = Pattern.compile(
            "(?<!\\d)(?:(\\d{4})\\s*[.\\-/년]\\s*)?(\\d{1,2})\\s*[.\\-/월]\\s*(\\d{1,2})(?!\\d)");

    private static final Pattern LABELLED_ROW = Pattern.compile("^([^:：|]{2,45})[:：|]\\s*(.{1,300})$");
    private static final Pattern APPROXIMATE_DATE = Pattern.compile(
            "(?:20\\d{2}(?:년|[.])\\s*)?(?:[0-9]{1,2}(?:(?:월|[.])\\s*(?:상순|중순|하순|초순|초|중|말경|말)?|\\s+(?:상순|중순|하순|초순|초|중|말경|말))"
                    + "(?:\\s*[~∼〜]\\s*[0-9]{1,2}(?:월|[.])\\s*(?:상순|중순|하순|초순|초|중|말경|말)?)?"
                    + "(?:\\s*[(（]?예정[)）]?)?|미정|추후(?:\\s*공지|\\s*안내)?|개별\\s*(?:안내|통보)(?:\\s*예정)?)");
    private static final Pattern REGIONAL_SUFFIX = Pattern.compile("\\)\\s*([가-힣]{2,10})$");

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
                // 서울·울산처럼 짧은 전형 구분도 근거 안에 실제로 있으면 보존한다.
                String note = row.note() != null && stripWhitespace(row.note()).length() >= 2
                        && stripWhitespace(row.evidence()).contains(stripWhitespace(row.note())) ? row.note() : null;
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


    /** 구조가 명확한 원문 행은 모델이 빠뜨려도 일정에 포함한다. */
    public static List<Item> withSourceEvidence(ParsedNotice notice, String body, LocalDate referenceDate) {
        List<Item> items = new ArrayList<>(sourceItems(body, referenceDate));
        if (notice != null) {
            for (Item candidate : resolve(notice, body, referenceDate)) {
                if (candidate.note() != null) {
                    items.removeIf(item -> item.note() == null && sameStageAndDates(item, candidate));
                }
                if (items.size() >= ScholarshipTimelineValidator.MAX_ITEMS) break;
                if (items.stream().noneMatch(item -> sameStageAndDates(item, candidate)
                        && (candidate.note() == null || Objects.equals(item.note(), candidate.note())))) {
                    items.add(candidate);
                }
            }
        }
        return items;
    }

    static boolean hasScheduleLabel(String line) {
        var row = LABELLED_ROW.matcher(line);
        return row.matches() && sourceStageOf(row.group(1)) != null;
    }

    private static TimelineStageCode sourceStageOf(String label) {
        String compact = label.strip().replaceFirst("^[○◦•※\\s-]+", "")
                .replaceFirst("^\\d+[.)）]\\s*", "")
                .replaceFirst("^[가-하][.．)]\\s*", "").replaceAll("\\s+", "");
        if (!compact.matches("(서류(?:심사|평가)(?:기간|일정)?"
                + "|(?:1차|서류)(?:합격(?:자)?(?:및면접대상자)?|전형)?(?:결과)?발표"
                + "|(?:[1-9]차)?면접(?:심사|전형)?(?:일|일정|기간)?"
                + "|(?:최종)?합격(?:자)?발표|최종(?:선발|발표)|장학생(?:선발(?:일정|일)?|발표)"
                + "|장학금(?:액및)?지급(?:일|일정|시기)?"
                + "|장학(?:금|증서)수여식(?:일정|일)?)")) return null;
        return stageOf(compact + ":");
    }

    private static List<Item> sourceItems(String body, LocalDate referenceDate) {
        List<Item> items = new ArrayList<>();
        for (String line : body.lines().toList()) {
            if (items.size() >= ScholarshipTimelineValidator.MAX_ITEMS) break;
            var row = LABELLED_ROW.matcher(line.strip());
            if (!row.matches()) continue;
            String label = row.group(1) + ":";
            TimelineStageCode stage = sourceStageOf(row.group(1));
            if (stage == null) continue;
            String value = row.group(2).strip();
            var regional = stage == TimelineStageCode.INTERVIEW
                    ? regionalInterviews(label, value, line, referenceDate) : List.<Item>of();
            if (!regional.isEmpty()) {
                for (var item : regional) addSourceItem(items, item);
                continue;
            }
            List<Point> points = dates(value, referenceDate);
            if (points.size() == 2 && TemporalCandidateParser.hasRangeSeparator(value)) {
                addSourceItem(items, sourceItem(stage, label, TimelineDateType.RANGE,
                        points.get(0).date(), points.get(1).date(), null, null, line));
            } else if (points.size() == 1 && SOURCE_DATE.matcher(value).lookingAt()) {
                addSourceItem(items, sourceItem(stage, label, TimelineDateType.SINGLE,
                        points.get(0).date(), null, null, null, line));
            } else if (points.isEmpty() && APPROXIMATE_DATE.matcher(value).matches()) {
                addSourceItem(items, sourceItem(stage, label, TimelineDateType.TBD, null, null, value, null, line));
            }
        }
        return items;
    }

    private static List<Item> regionalInterviews(String label, String value, String evidence, LocalDate referenceDate) {
        String[] rows = value.split("\\s+/\\s+");
        if (rows.length != 2) return List.of();
        List<Point> points = dates(value, referenceDate);
        if (points.size() != rows.length) return List.of();
        List<Item> result = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            var location = REGIONAL_SUFFIX.matcher(TemporalCandidateParser.normalizeDates(rows[i]));
            if (!location.find()) return List.of();
            Item item = sourceItem(TimelineStageCode.INTERVIEW, label, TimelineDateType.SINGLE,
                    points.get(i).date(), null, null, location.group(1), evidence);
            if (item == null) return List.of();
            result.add(item);
        }
        return Objects.equals(result.get(0).note(), result.get(1).note()) ? List.of() : result;
    }

    private static List<Point> dates(String value, LocalDate referenceDate) {
        return TemporalCandidateParser.dates(value, referenceDate, new ArrayList<>());
    }

    private static Item sourceItem(TimelineStageCode stage, String label, TimelineDateType type,
            LocalDate start, LocalDate end, String dateText, String note, String evidence) {
        try {
            return ScholarshipTimelineValidator.normalize(List.of(new TimelineItemRequest(
                    stage.name(), customTitle(label), type.name(), start, end, dateText, note, evidence))).get(0);
        } catch (CustomException invalid) {
            return null;
        }
    }

    private static void addSourceItem(List<Item> items, Item candidate) {
        if (candidate != null && items.size() < ScholarshipTimelineValidator.MAX_ITEMS
                && items.stream().noneMatch(item -> sameStageAndDates(item, candidate)
                        && Objects.equals(item.note(), candidate.note()))) {
            items.add(candidate);
        }
    }

    private static boolean sameStageAndDates(Item item, Item candidate) {
        return item.stageCode() == candidate.stageCode() && item.dateType() == candidate.dateType()
                && Objects.equals(item.title(), candidate.title())
                && Objects.equals(item.startDate(), candidate.startDate())
                && Objects.equals(item.endDate(), candidate.endDate())
                && stripWhitespace(item.dateText()).equals(stripWhitespace(candidate.dateText()));
    }

    private static String stripWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
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
