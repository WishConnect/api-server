package com.wishconnect.domain.scholarship.util;

import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
조건 원문(valueString)에서 정규식으로 수치 기준을 추출하는 룰 기반 정제기입니다.
LLM 없이 동작하며, 정형 패턴만 확실하게 추출하고 애매하면 건드리지 않습니다(추측 금지).
규약은 LLM 추출과 동일: 평점은 100배 정수(4.5 만점), 소득은 분위(1~10), 학년/학기는 학기 단위(1~12).
 */
public final class ConditionRuleParser {

	/** 소득: "8분위 이하/이내/미만", "학자금지원 5구간 이내" */
	private static final Pattern INCOME_LTE =
			Pattern.compile("(\\d{1,2})\\s*(?:분위|구간)\\s*(?:이하|이내|미만)");
	/** 소득 범위: "0~3분위", "1-4구간" → 상한값 이하로 해석 */
	private static final Pattern INCOME_RANGE =
			Pattern.compile("\\d{1,2}\\s*(?:~|-)\\s*(\\d{1,2})\\s*(?:분위|구간)");

	/** 성적: "2.75 이상", "평점 3.0이상" (소수점 형태만 — 정수 단독은 학점수와 혼동되므로 제외) */
	private static final Pattern GPA_GTE =
			Pattern.compile("(\\d)[.,](\\d{1,2})\\s*(?:점\\s*)?이상");

	/** 학기 범위: "대학2학기부터 대학8학기까지", "2학기~8학기" */
	private static final Pattern SEMESTER_RANGE =
			Pattern.compile("(\\d{1,2})\\s*학기\\s*(?:부터|~|-|이상)?\\s*(?:대학)?\\s*(\\d{1,2})\\s*학기");

	/** 단일 학기 하한: "3학기 이상" */
	private static final Pattern SEMESTER_GTE = Pattern.compile("(\\d{1,2})\\s*학기\\s*이상");

	/** 학년: "2학년", "2~4학년" — 학기로 환산(N학년 = 2N-1 ~ 2N) */
	private static final Pattern GRADE_RANGE = Pattern.compile("(\\d)\\s*(?:~|-)\\s*(\\d)\\s*학년");
	private static final Pattern GRADE_SINGLE = Pattern.compile("(\\d)\\s*학년");

	private ConditionRuleParser() {
	}

	public record Extracted(ConditionOperator operator, Integer valueInt, Integer valueIntMax) {
	}

	public static Optional<Extracted> parse(ConditionType type, String rawText) {
		if (rawText == null || rawText.isBlank()) {
			return Optional.empty();
		}
		String text = rawText.replaceAll("\\s+", " ").trim();
		return switch (type) {
			case INCOME_CRITERIA -> parseIncome(text);
			case ACADEMIC_CRITERIA -> parseGpa(text);
			case GRADE_LEVEL -> parseSemester(text);
			default -> Optional.empty();
		};
	}

	private static Optional<Extracted> parseIncome(String text) {
		Matcher range = INCOME_RANGE.matcher(text);
		if (range.find()) {
			int level = Integer.parseInt(range.group(1));
			if (level >= 1 && level <= 10) {
				return Optional.of(new Extracted(ConditionOperator.LTE, level, null));
			}
		}
		Matcher m = INCOME_LTE.matcher(text);
		if (m.find()) {
			int level = Integer.parseInt(m.group(1));
			if (level >= 1 && level <= 10) {
				return Optional.of(new Extracted(ConditionOperator.LTE, level, null));
			}
		}
		return Optional.empty();
	}

	private static Optional<Extracted> parseGpa(String text) {
		Matcher m = GPA_GTE.matcher(text);
		if (m.find()) {
			int whole = Integer.parseInt(m.group(1));
			String fraction = m.group(2);
			int value = whole * 100 + (fraction.length() == 1
					? Integer.parseInt(fraction) * 10 : Integer.parseInt(fraction));
			if (value >= 100 && value <= 450) {
				return Optional.of(new Extracted(ConditionOperator.GTE, value, null));
			}
		}
		return Optional.empty();
	}

	private static Optional<Extracted> parseSemester(String text) {
		Matcher range = SEMESTER_RANGE.matcher(text);
		if (range.find()) {
			int min = Integer.parseInt(range.group(1));
			int max = Integer.parseInt(range.group(2));
			if (min >= 1 && max <= 12 && min <= max) {
				return Optional.of(new Extracted(ConditionOperator.BETWEEN, min, max));
			}
		}
		Matcher gte = SEMESTER_GTE.matcher(text);
		if (gte.find()) {
			int min = Integer.parseInt(gte.group(1));
			if (min >= 1 && min <= 12) {
				return Optional.of(new Extracted(ConditionOperator.GTE, min, null));
			}
		}
		Matcher gradeRange = GRADE_RANGE.matcher(text);
		if (gradeRange.find()) {
			int minGrade = Integer.parseInt(gradeRange.group(1));
			int maxGrade = Integer.parseInt(gradeRange.group(2));
			if (minGrade >= 1 && maxGrade <= 6 && minGrade <= maxGrade) {
				return Optional.of(new Extracted(ConditionOperator.BETWEEN, minGrade * 2 - 1, maxGrade * 2));
			}
		}
		Matcher grade = GRADE_SINGLE.matcher(text);
		if (grade.find()) {
			int g = Integer.parseInt(grade.group(1));
			if (g >= 1 && g <= 6) {
				return Optional.of(new Extracted(ConditionOperator.BETWEEN, g * 2 - 1, g * 2));
			}
		}
		return Optional.empty();
	}
}
