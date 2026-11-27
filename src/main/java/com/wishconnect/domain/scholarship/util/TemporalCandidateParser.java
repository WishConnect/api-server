package com.wishconnect.domain.scholarship.util;

import com.wishconnect.domain.scholarship.dto.ParsedNotice;
import com.wishconnect.domain.scholarship.dto.PipelineNoticeInput;
import com.wishconnect.domain.scholarship.dto.TemporalParseResult;
import com.wishconnect.domain.scholarship.dto.TemporalParseResult.Period;
import com.wishconnect.domain.scholarship.dto.TemporalParseResult.Point;
import com.wishconnect.domain.scholarship.dto.TemporalParseResult.Precision;
import com.wishconnect.domain.scholarship.dto.TemporalParseResult.Stage;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 원문에서 모집기간과 일정 후보를 검증하며 날짜와 시각의 정밀도를 보존한다. */
@Component
public class TemporalCandidateParser {

    public static final String VERSION = "temporal-v2.0.0-rc.4";
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(?:(20\\d{2})\\s*[.\\-/년]\\s*)?(\\d{1,2})\\s*[.\\-/월]\\s*(\\d{1,2})(?!\\d)");
    private static final Pattern TIME = Pattern.compile(
            "^\\s*[.일]?\\s*(?:\\([^)]{0,8}\\)\\s*)?[,，]?\\s*(?:(오전|오후)\\s*)?"
                    + "(\\d{1,2})\\s*(?::\\s*(\\d{2})|시(?:\\s*(\\d{1,2})\\s*분)?)");

    private static final Pattern SHORT_KOREAN_RANGE = Pattern.compile(
            "(\\d{1,2})\\s*월\\s*\\d{1,2}\\s*일(?:\\s*\\([^)]{0,8}\\))?\\s*[~∼〜–-]\\s*(\\d{1,2})\\s*일");
    private static final Pattern RANGE_SEPARATOR = Pattern.compile("[~∼〜–]|부터|(?<=\\s)-(?=\\s)");

    private static final Pattern QUOTED_YEAR = Pattern.compile(
            "(?<!\\d)(?:20\\d{2}|['’‘`]\\d{2})\\s*[.\\-/년]\\s*\\d{1,2}");
    private static final Pattern EXPLICIT_DEADLINE = Pattern.compile(
            "마감|기한|까지|[~∼〜]\\s*(?:20\\d{2}\\D+)?\\d");
    private static final Pattern INTRADAY_RANGE = Pattern.compile("[~∼〜]\\s*(?:오전|오후)?\\s*\\d{1,2}\\s*[:시]");

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
                var stage = NoticeTimelineParser.stageOf(row.evidence());
                if (stage == null || !stage.name().equals(row.stageCode())) {
                    issues.add("TIMELINE_STAGE_AMBIGUOUS");
                    continue;
                }
                if ("TBD".equals(row.dateType())) {
                    if (row.startDate() == null && row.endDate() == null && row.dateText() != null
                            && NoticeTimelineParser.isVagueDate(row.dateText(), row.evidence())) {
                        stages.add(new Stage(stage.name(), "TBD", null, null, row.dateText(), row.evidence()));
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
                stages.add(new Stage(stage.name(), row.dateType(), start, end, null, row.evidence()));
            }
        }
        if (input.truncated()) issues.add("INPUT_TRUNCATED");
        return new TemporalParseResult(period, List.copyOf(new LinkedHashSet<>(stages)), List.copyOf(issues));
    }

    /** 본문을 이미 추출한 실제 대학 파싱에서도 같은 모집기간 검증을 사용한다. */
    public Period resolveRecruitmentPeriod(String title, String body, LocalDate referenceDate, ParsedNotice notice) {
        return resolveRecruitmentPeriod(title, body, referenceDate, notice, new ArrayList<>());
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
        return resolveRecruitmentPeriod(input.title(), input.body(), input.referenceDate(), notice, issues);
    }

    private Period resolveRecruitmentPeriod(String title, String body, LocalDate referenceDate,
            ParsedNotice notice, List<String> issues) {
        String context = (title == null ? "" : title) + "\n" + body;
        String evidence = notice == null ? null : notice.periodEvidence();
        if (grounded(evidence, context)) {
            boolean fromTitle = title != null && applicationTitle(title) && grounded(evidence, title);
            return periodFromEvidence(evidence, fromTitle, referenceDate, notice.applicationStart(),
                    notice.applicationEnd(), issues);
        }
        Period source = resolveExplicitRecruitmentPeriod(title, body, referenceDate);
        if (source == null) issues.add("PERIOD_EVIDENCE_NOT_FOUND");
        return source;
    }

    /** 원문에 명시된 접수 구간 또는 마감만 보완한다. 서로 다른 신청기간이 있으면 자동 선택하지 않는다. */
    public Period resolveExplicitRecruitmentPeriod(String title, String body, LocalDate referenceDate) {
        Period found = null;
        for (String line : body.split("\\R")) {
            if (line.length() > 350 || !hasApplicationLabel(line)) continue;
            Period candidate = periodFromEvidence(line, false, referenceDate, null, null, new ArrayList<>());
            if (candidate == null) continue;
            found = found == null ? candidate : combinePeriod(found, candidate);
            if (found == null) return null;
        }
        return found != null || title == null || !applicationTitle(title) ? found
                : periodFromEvidence(title, true, referenceDate, null, null, new ArrayList<>());
    }

    private static Period combinePeriod(Period first, Period second) {
        Point end = combinePoint(first.end(), second.end());
        Point start = combinePoint(first.start(), second.start());
        if (end == null || (start == null && first.start() != null && second.start() != null)) return null;
        String evidence = end == second.end() ? second.evidence() : first.evidence();
        return new Period(start, end, evidence);
    }

    private static Point combinePoint(Point first, Point second) {
        if (first == null) return second;
        if (second == null) return first;
        if (!first.date().equals(second.date()) || first.time() != null && second.time() != null
                && !Objects.equals(first.time(), second.time())) return null;
        return first.time() == null ? second : first;
    }

    static boolean hasApplicationLabel(String evidence) {
        return evidence.replaceAll("\\s+", "").matches(
                ".*(신청기간|신청일정|접수일정|지원일정|접수기간|모집기간|지원서제출|원서접수|서류접수|지원접수|신청기한|접수기한"
                        + "|제출기한|제출기간|지원기간|신청마감|접수마감|제출마감|지원마감|모집마감).*");
    }

    private Period periodFromEvidence(String evidence, boolean fromTitle, LocalDate referenceDate,
            String claimedStart, String claimedEnd, List<String> issues) {
        if (UnivNoticeLlmParser.isNonApplicationPeriod(evidence)
                || (!fromTitle && !hasApplicationLabel(evidence))
                || evidence.replaceAll("\\s+", "").matches(".*(확인서|서류합격|면접|최종발표|근무기간|지급기간).*")) {
            issues.add("NON_APPLICATION_PERIOD");
            return null;
        }
        List<Point> points = dates(evidence, referenceDate, issues);
        if (points.isEmpty() || points.size() > 2
                || points.size() == 2 && !hasRangeSeparator(evidence)
                || points.size() == 1 && INTRADAY_RANGE.matcher(normalizeDates(evidence)).find()) {
            issues.add("PERIOD_DATE_RELATION_AMBIGUOUS");
            return null;
        }
        boolean sourceHasYear = QUOTED_YEAR.matcher(evidence).find();
        Point end = points.get(points.size() - 1);
        Point start = points.size() == 2 ? points.get(0) : null;
        if ((claimedEnd != null && !matchesPeriodDate(claimedEnd, end, sourceHasYear))
                || (claimedStart != null && start != null && !matchesPeriodDate(claimedStart, start, sourceHasYear))) {
            issues.add("PERIOD_DATE_MISMATCH");
            return null;
        }
        if (start == null && claimedEnd == null && !EXPLICIT_DEADLINE.matcher(evidence).find()) {
            issues.add("PERIOD_END_UNCONFIRMED");
            return null;
        }
        if (start != null && (reversed(start, end)
                || ChronoUnit.DAYS.between(start.date(), end.date()) > 300)) {
            issues.add("PERIOD_RANGE_INVALID");
            return null;
        }
        return new Period(start, end, evidence);
    }

    private static boolean matchesPeriodDate(String claimed, Point source, boolean sourceHasYear) {
        try {
            LocalDate date = LocalDate.parse(claimed);
            return sourceHasYear ? date.equals(source.date())
                    : date.getMonth() == source.date().getMonth() && date.getDayOfMonth() == source.date().getDayOfMonth();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    static boolean hasRangeSeparator(String evidence) {
        return RANGE_SEPARATOR.matcher(normalizeDates(evidence)).find();
    }

    static String normalizeDates(String evidence) {
        String normalized = Normalizer.normalize(evidence, Normalizer.Form.NFKC);
        var range = SHORT_KOREAN_RANGE.matcher(normalized);
        return range.replaceAll(match -> {
            String text = match.group();
            int day = match.start(2) - match.start();
            return java.util.regex.Matcher.quoteReplacement(text.substring(0, day)
                    + match.group(1) + "월 " + text.substring(day));
        });
    }

    static List<Point> dates(String evidence, LocalDate reference, List<String> issues) {
        List<Point> result = new ArrayList<>();
        if (evidence == null || reference == null) return result;
        String normalized = normalizeDates(evidence).replaceAll("['’‘`](\\d{2})\\s*[.년]", "20$1.");
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

}
