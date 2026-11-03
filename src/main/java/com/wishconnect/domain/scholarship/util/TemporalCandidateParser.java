package com.wishconnect.domain.scholarship.util;

import com.wishconnect.domain.scholarship.dto.ParsedNotice;
import com.wishconnect.domain.scholarship.dto.PipelineNoticeInput;
import com.wishconnect.domain.scholarship.dto.TemporalParseResult;
import com.wishconnect.domain.scholarship.dto.TemporalParseResult.Period;
import com.wishconnect.domain.scholarship.dto.TemporalParseResult.Point;
import com.wishconnect.domain.scholarship.dto.TemporalParseResult.Precision;
import com.wishconnect.domain.scholarship.dto.TemporalParseResult.Stage;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** v2 비교용 검증기. 결과를 운영 엔티티에 적용하는 책임은 갖지 않는다. */
@Component
public class TemporalCandidateParser {

    public static final String VERSION = "temporal-v2.0.0-rc.1";
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(?:(20\\d{2})\\s*[.\\-/년]\\s*)?(\\d{1,2})\\s*[.\\-/월]\\s*(\\d{1,2})(?!\\d)");
    private static final Pattern TIME = Pattern.compile(
            "^\\s*[.일]?\\s*(?:\\([^)]{0,8}\\)\\s*)?(?:(오전|오후)\\s*)?"
                    + "(\\d{1,2})\\s*(?::\\s*(\\d{2})|시(?:\\s*(\\d{1,2})\\s*분)?)");

    public TemporalParseResult resolve(PipelineNoticeInput input, ParsedNotice notice) {
        List<String> issues = new ArrayList<>();
        Period period = resolvePeriod(input, notice, issues);
        List<Stage> stages = new ArrayList<>();
        if (notice.timeline() != null) {
            for (var row : notice.timeline()) {
                if (stages.size() == 10) break;
                if (row == null || !grounded(row.evidence(), input.evidenceText())) {
                    issues.add("TIMELINE_EVIDENCE_NOT_FOUND");
                    continue;
                }
                String stage = stageOf(row.evidence());
                if (stage == null || !stage.equals(row.stageCode())) {
                    issues.add("TIMELINE_STAGE_AMBIGUOUS");
                    continue;
                }
                if ("TBD".equals(row.dateType())) {
                    if (row.startDate() == null && row.endDate() == null && row.dateText() != null
                            && row.evidence().contains(row.dateText())
                            && row.dateText().matches(".*(미정|예정|추후|공지|통보|\\d{1,2}월\\s*(중|초|말)).*")) {
                        stages.add(new Stage(stage, "TBD", null, null, row.dateText(), row.evidence()));
                    } else issues.add("TIMELINE_TBD_NOT_GROUNDED");
                    continue;
                }
                List<Point> points = dates(row.evidence(), input.referenceDate(), issues);
                boolean single = "SINGLE".equals(row.dateType());
                if ((!single && !"RANGE".equals(row.dateType()))
                        || points.size() != (single ? 1 : 2)) {
                    issues.add("TIMELINE_DATE_RELATION_AMBIGUOUS");
                    continue;
                }
                Point start = points.get(0), end = points.get(points.size() - 1);
                if (!matches(row.startDate(), start) || (!single && !matches(row.endDate(), end))
                        || (single && row.endDate() != null && !matches(row.endDate(), end))
                        || reversed(start, end)) {
                    issues.add("TIMELINE_DATE_MISMATCH");
                    continue;
                }
                stages.add(new Stage(stage, row.dateType(), start, end, null, row.evidence()));
            }
        }
        if (input.truncated()) issues.add("INPUT_TRUNCATED");
        return new TemporalParseResult(period, List.copyOf(new LinkedHashSet<>(stages)), List.copyOf(issues));
    }

    private Period resolvePeriod(PipelineNoticeInput input, ParsedNotice notice, List<String> issues) {
        // API의 날짜는 날짜 정밀도로 보존한다. 자유 텍스트로 정상 API 필드를 덮지 않는다.
        if (input.rawJson() != null) {
            Point start = apiDate(input.rawJson().get("모집시작일"));
            Point end = apiDate(input.rawJson().get("모집종료일"));
            if (end != null && (start == null || !end.date().isBefore(start.date()))) {
                return new Period(start, end, "API: 모집시작일/모집종료일");
            }
        }
        String evidence = notice.periodEvidence();
        if (!grounded(evidence, input.evidenceText())) {
            // 제목이 명확한 기간을 포함한 경우에만 결정적으로 복구한다.
            String title = input.title() == null ? "" : input.title();
            if (applicationTitle(title)) evidence = title;
            else {
                issues.add("PERIOD_EVIDENCE_NOT_FOUND");
                return null;
            }
        }
        boolean fromTitle = input.title() != null && applicationTitle(input.title())
                && grounded(evidence, input.title());
        boolean applicationLabel = evidence.replaceAll("\\s+", "")
                .matches(".*(신청기간|접수기간|모집기간|지원서제출|신청기한|접수기한|지원기간|신청마감|접수마감).*" );
        if (UnivNoticeLlmParser.isNonApplicationPeriod(evidence)
                || (!fromTitle && !applicationLabel)
                || evidence.matches(".*(확인서|서류합격|면접|최종발표|근무기간|지급기간).*")) {
            issues.add("NON_APPLICATION_PERIOD");
            return null;
        }
        List<Point> points = dates(evidence, input.referenceDate(), issues);
        if (points.isEmpty() || points.size() > 2) {
            issues.add("PERIOD_DATE_RELATION_AMBIGUOUS");
            return null;
        }
        if ((notice.applicationStart() != null || notice.applicationEnd() != null)
                && (!matches(notice.applicationEnd(), points.get(points.size() - 1))
                || (notice.applicationStart() != null && points.size() == 2
                    && !matches(notice.applicationStart(), points.get(0))))) {
            issues.add("PERIOD_DATE_MISMATCH");
            return null;
        }
        if (points.size() == 1 && notice.applicationEnd() == null) {
            issues.add("PERIOD_END_UNCONFIRMED");
            return null;
        }
        Point start = points.size() == 2 ? points.get(0) : null;
        Point end = points.get(points.size() - 1);
        if (start != null && (reversed(start, end)
                || ChronoUnit.DAYS.between(start.date(), end.date()) > 300)) {
            issues.add("PERIOD_RANGE_INVALID");
            return null;
        }
        return new Period(start, end, evidence);
    }

    private static List<Point> dates(String evidence, LocalDate reference, List<String> issues) {
        List<Point> result = new ArrayList<>();
        if (evidence == null || reference == null) return result;
        String normalized = evidence.replaceAll("['’‘`](\\d{2})\\s*[.년]", "20$1.");
        var matcher = DATE.matcher(normalized);
        Integer inheritedYear = null;
        int previousMonth = 0;
        while (matcher.find()) {
            try {
                int month = Integer.parseInt(matcher.group(2));
                int day = Integer.parseInt(matcher.group(3));
                int year = matcher.group(1) != null ? Integer.parseInt(matcher.group(1))
                        : inheritedYear != null ? inheritedYear : reference.getYear();
                if (matcher.group(1) == null && previousMonth == 12 && month == 1) year++;
                LocalDate date = LocalDate.of(year, month, day);
                if (matcher.group(1) == null && inheritedYear == null) {
                    date = UnivNoticeLlmParser.withYearNear(date, reference);
                    issues.add("YEAR_INFERRED");
                }
                inheritedYear = date.getYear();
                previousMonth = month;
                LocalTime time = null;
                var clock = TIME.matcher(normalized.substring(matcher.end()));
                if (clock.find()) {
                    int hour = Integer.parseInt(clock.group(2));
                    int minute = clock.group(3) != null ? Integer.parseInt(clock.group(3))
                            : clock.group(4) != null ? Integer.parseInt(clock.group(4)) : 0;
                    if (clock.group(1) != null) {
                        if (hour < 1 || hour > 12) throw new IllegalArgumentException("clock hour");
                        hour = hour % 12 + ("오후".equals(clock.group(1)) ? 12 : 0);
                    }
                    if (hour == 24 && minute == 0) {
                        date = date.plusDays(1);
                        hour = 0;
                    }
                    time = LocalTime.of(hour, minute);
                }
                result.add(new Point(date, time, time == null ? Precision.DATE_ONLY : Precision.DATE_TIME));
            } catch (RuntimeException invalid) {
                issues.add("SOURCE_DATE_INVALID");
                return List.of();
            }
        }
        // 같은 날의 시간 범위는 끝 시각을 첫 시각으로 오인하지 않는다. 후속 지원 전까지 명시적으로 제외.
        if (result.size() == 1 && evidence.matches(".*[~∼〜]\\s*(오전|오후)?\\s*\\d{1,2}\\s*[:시].*")) {
            issues.add("INTRADAY_RANGE_UNSUPPORTED");
            return List.of();
        }
        return result;
    }

    private static boolean applicationTitle(String title) {
        return title.matches(".*(신청|모집|접수|선발).*" )
                && !title.matches(".*(결과|발표|지급|근무|활동).*" );
    }

    private static boolean reversed(Point start, Point end) {
        if (!start.date().equals(end.date()) || start.time() == null || end.time() == null) {
            return end.date().isBefore(start.date());
        }
        return end.time().isBefore(start.time());
    }

    private static boolean matches(String claimed, Point source) {
        try { return claimed != null && LocalDate.parse(claimed).equals(source.date()); }
        catch (RuntimeException invalid) { return false; }
    }

    private static boolean grounded(String evidence, String context) {
        return UnivNoticeLlmParser.isEvidenceGrounded(evidence, context);
    }

    private static Point apiDate(Object value) {
        if (value == null) return null;
        for (var format : List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.BASIC_ISO_DATE)) {
            try { return new Point(LocalDate.parse(value.toString(), format), null, Precision.DATE_ONLY); }
            catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static String stageOf(String evidence) {
        String text = evidence.replaceAll("\\s+", "");
        List<String> stages = new ArrayList<>();
        if (text.matches(".*(접수기간|신청기간|모집기간|확인서|제출기한|제출기간).*")) return null;
        if (text.matches(".*(서류|1차).*(발표|결과|합격).*")) stages.add("DOC_RESULT");
        else if (text.matches(".*(서류심사|서류평가).*")) stages.add("DOC_REVIEW");
        if (text.contains("면접")) stages.add("INTERVIEW");
        if (text.matches(".*(최종|선발|합격자|장학생).*(발표|결과|통보).*")) {
            if (!text.matches(".*(서류|1차).*")) stages.add("FINAL_RESULT");
        }
        if (text.matches(".*(장학금|지원금).*(지급|입금).*")) stages.add("PAYMENT");
        return stages.size() == 1 ? stages.get(0) : null;
    }
}
