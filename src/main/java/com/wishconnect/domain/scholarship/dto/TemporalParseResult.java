package com.wishconnect.domain.scholarship.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 후보 전용 결과. DATE_ONLY에 임의의 자정/23:59:59를 부여하지 않는다. */
public record TemporalParseResult(Period period, List<Stage> timeline, List<String> issues) {
    public enum Precision { DATE_ONLY, DATE_TIME }
    public record Point(LocalDate date, LocalTime time, Precision precision) { }
    public record Period(Point start, Point end, String evidence) { }
    public record Stage(String stageCode, String dateType, Point start, Point end,
                        String dateText, String evidence) { }
}
