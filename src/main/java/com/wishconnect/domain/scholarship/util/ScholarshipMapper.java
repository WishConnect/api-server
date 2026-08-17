package com.wishconnect.domain.scholarship.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.wishconnect.domain.scholarship.config.ScholarshipApiProperties;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.entity.ScholarshipDocument;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/*
공공데이터 원본 JSON을 서비스용 장학금 엔티티 구조로 변환하는 매퍼입니다.
날짜, 금액, 제출서류, 매칭 조건 파싱을 담당해 SyncService가 저장 흐름에만 집중하도록 분리합니다.
 */
@Component
public class ScholarshipMapper {

	private static final Pattern INCOME_PERCENT_PATTERN = Pattern.compile("중위소득\\s*(\\d+)\\s*%");
	private static final Pattern AID_SECTION_PATTERN = Pattern.compile("(\\d+)\\s*구간");
	private static final Pattern GPA_PATTERN = Pattern.compile("(?:성적\\s*평균|평점\\s*평균|평균|학점|성적이?)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:점)?\\s*(?:/\\s*\\d+(?:\\.\\d+)?)?\\s*이상|(\\d+\\.\\d+)\\s*점\\s*이상");
	private static final Pattern CREDIT_PATTERN = Pattern.compile("(\\d+)\\s*학점\\s*이상");
	private static final Pattern RESIDENCY_YEAR_PATTERN = Pattern.compile("(\\d+)\\s*년\\s*이상");
	private static final Pattern SEMESTER_PATTERN = Pattern.compile("대학\\s*(\\d+)\\s*학기");

	public Scholarship toScholarship(
		JsonNode item,
		Scholarship existingScholarship,
		ScholarshipApiProperties properties
	) {
		String title = defaultText(readText(item, "상품명"), "제목 없음");
		String provider = readText(item, "운영기관명");
		String summary = readText(item, "지원내역 상세내용");
		String description = buildDescription(item);
		LocalDateTime startAt = readDate(item, "모집시작일");
		LocalDateTime endAt = readDate(item, "모집종료일");
		Integer selectionCount = parseFirstInteger(readText(item, "선발인원 상세내용"));
		Long amount = parseAmount(readText(item, "지원내역 상세내용"));
		String homepageUrl = readText(item, "홈페이지 주소");
		String dedupKey = createDedupKey(item);
		RecruitmentStatus recruitmentStatus = resolveRecruitmentStatus(startAt, endAt);
		boolean active = recruitmentStatus != RecruitmentStatus.CLOSED;

		if (existingScholarship == null) {
			Scholarship scholarship = Scholarship.builder()
				.title(title)
				.provider(provider)
				.summary(summary)
				.description(description)
				.scholarshipType(ScholarshipType.EXTERNAL)
				.applicationStartAt(startAt)
				.applicationEndAt(endAt)
				.recruitmentStatus(recruitmentStatus)
				.selectionCount(selectionCount)
				.amount(amount)
				.primarySource(properties.sourceName())
				.dedupKey(dedupKey)
				.homepageUrl(homepageUrl)
				.build();
			scholarship.updateActive(active);
			return scholarship;
		}

		existingScholarship.updateFromApi(
			title,
			provider,
			summary,
			description,
			ScholarshipType.EXTERNAL,
			startAt,
			endAt,
			recruitmentStatus,
			selectionCount,
			amount,
			properties.sourceName(),
			dedupKey,
			homepageUrl
		);
		existingScholarship.updateActive(active);
		return existingScholarship;
	}

	/*
	월별 엔드포인트가 달라도 같은 공고를 하나의 scholarship으로 묶기 위한 키입니다.
	모집기간이 달라진 재공고는 새 장학금으로 봐야 하므로 시작일/종료일을 키에 포함합니다.
	 */
	public String createDedupKey(JsonNode item) {
		String keyText = String.join("|",
			normalizeForKey(readText(item, "상품명")),
			normalizeForKey(readText(item, "운영기관명")),
			normalizeForKey(readText(item, "모집시작일")),
			normalizeForKey(readText(item, "모집종료일"))
		);
		return sha256(keyText);
	}

	public List<ScholarshipDocument> toDocuments(Scholarship scholarship, JsonNode item) {
		String documentText = readText(item, "제출서류 상세내용");
		if (!StringUtils.hasText(documentText) || isEmptyValue(documentText)) {
			return List.of();
		}

		String normalized = documentText
			.replace("※자세한 사항은 첨부파일 또는 홈페이지 참고", "")
			.replace("※ 자세한 사항은 첨부파일 또는 홈페이지 참고", "")
			.trim();

		String[] tokens = normalized.split("○");
		List<ScholarshipDocument> documents = new ArrayList<>();
		int displayOrder = 0;
		for (String token : tokens) {
			String name = cleanText(token);
			if (!StringUtils.hasText(name) || isEmptyValue(name)) {
				continue;
			}
			documents.add(ScholarshipDocument.builder()
				.scholarship(scholarship)
				.name(limit(name, 200))
				.essay(isEssayDocument(name))
				.displayOrder(displayOrder++)
				.build());
		}
		return documents;
	}

	public List<ScholarshipCondition> toConditions(Scholarship scholarship, JsonNode item) {
		List<ScholarshipCondition> conditions = new ArrayList<>();
		addCondition(conditions, scholarship, ConditionType.UNIVERSITY_TYPE, readText(item, "대학구분"));
		addCondition(conditions, scholarship, ConditionType.MAJOR_FIELD, readText(item, "학과구분"));
		addGradeLevelCondition(conditions, scholarship, readText(item, "학년구분"));
		addAcademicCondition(conditions, scholarship, readText(item, "성적기준 상세내용"));
		addIncomeCondition(conditions, scholarship, readText(item, "소득기준 상세내용"));
		addRegionResidencyCondition(conditions, scholarship, readText(item, "지역거주여부 상세내용"));
		addCondition(conditions, scholarship, ConditionType.SPECIFIC_QUALIFICATION, readText(item, "특정자격 상세내용"));
		addCondition(conditions, scholarship, ConditionType.RESTRICTION, readText(item, "자격제한 상세내용"));
		addCondition(conditions, scholarship, ConditionType.FINANCIAL_AID_TYPE, readText(item, "학자금유형구분"));
		addCondition(conditions, scholarship, ConditionType.RECOMMENDATION_REQUIRED, readText(item, "추천필요여부 상세내용"));
		return conditions;
	}

	public boolean isClosed(JsonNode item) {
		LocalDateTime endAt = readDate(item, "모집종료일");
		return endAt != null && LocalDateTime.now().isAfter(endAt);
	}

	private void addCondition(
		List<ScholarshipCondition> conditions,
		Scholarship scholarship,
		ConditionType conditionType,
		String value
	) {
		if (!StringUtils.hasText(value) || isEmptyValue(value)) {
			return;
		}
		conditions.add(ScholarshipCondition.builder()
			.scholarship(scholarship)
			.conditionType(conditionType)
			.operator(ConditionOperator.EQ)
			.valueString(cleanText(value))
			.autoExtracted(false)
			.build());
	}

	private void addGradeLevelCondition(
		List<ScholarshipCondition> conditions,
		Scholarship scholarship,
		String value
	) {
		if (!StringUtils.hasText(value) || isEmptyValue(value)) {
			return;
		}

		Integer minSemester = null;
		Integer maxSemester = null;
		Matcher matcher = SEMESTER_PATTERN.matcher(value);
		while (matcher.find()) {
			int semester = Integer.parseInt(matcher.group(1));
			minSemester = minSemester == null ? semester : Math.min(minSemester, semester);
			maxSemester = maxSemester == null ? semester : Math.max(maxSemester, semester);
		}

		if (value.contains("대학신입생")) {
			minSemester = minSemester == null ? 1 : Math.min(minSemester, 1);
			maxSemester = maxSemester == null ? 1 : Math.max(maxSemester, 1);
		}

		ConditionOperator operator = resolveRangeOperator(minSemester, maxSemester);
		addCondition(conditions, scholarship, ConditionType.GRADE_LEVEL, value, operator, minSemester, maxSemester);
	}

	private void addAcademicCondition(
		List<ScholarshipCondition> conditions,
		Scholarship scholarship,
		String value
	) {
		if (!StringUtils.hasText(value) || isEmptyValue(value)) {
			return;
		}

		Integer minimumGpa = parseScaledDecimal(value, GPA_PATTERN);
		if (minimumGpa != null) {
			addCondition(conditions, scholarship, ConditionType.ACADEMIC_CRITERIA, value, ConditionOperator.GTE, minimumGpa, null);
			return;
		}

		Integer minimumCredits = parseInteger(value, CREDIT_PATTERN);
		if (minimumCredits != null) {
			addCondition(conditions, scholarship, ConditionType.ACADEMIC_CRITERIA, value, ConditionOperator.GTE, minimumCredits, null);
			return;
		}

		addCondition(conditions, scholarship, ConditionType.ACADEMIC_CRITERIA, value);
	}

	private void addIncomeCondition(
		List<ScholarshipCondition> conditions,
		Scholarship scholarship,
		String value
	) {
		if (!StringUtils.hasText(value) || isEmptyValue(value)) {
			return;
		}

		Integer medianIncomePercent = parseInteger(value, INCOME_PERCENT_PATTERN);
		if (medianIncomePercent != null) {
			addCondition(conditions, scholarship, ConditionType.INCOME_CRITERIA, value, ConditionOperator.LTE, medianIncomePercent, null);
			return;
		}

		Integer aidSection = parseInteger(value, AID_SECTION_PATTERN);
		if (aidSection != null) {
			addCondition(conditions, scholarship, ConditionType.INCOME_CRITERIA, value, ConditionOperator.LTE, aidSection, null);
			return;
		}

		addCondition(conditions, scholarship, ConditionType.INCOME_CRITERIA, value);
	}

	private void addRegionResidencyCondition(
		List<ScholarshipCondition> conditions,
		Scholarship scholarship,
		String value
	) {
		if (!StringUtils.hasText(value) || isEmptyValue(value)) {
			return;
		}

		Integer minimumYears = parseInteger(value, RESIDENCY_YEAR_PATTERN);
		if (minimumYears != null) {
			addCondition(conditions, scholarship, ConditionType.REGION_RESIDENCY, value, ConditionOperator.GTE, minimumYears, null);
			return;
		}

		addCondition(conditions, scholarship, ConditionType.REGION_RESIDENCY, value);
	}

	private void addCondition(
		List<ScholarshipCondition> conditions,
		Scholarship scholarship,
		ConditionType conditionType,
		String value,
		ConditionOperator operator,
		Integer valueInt,
		Integer valueIntMax
	) {
		if (!StringUtils.hasText(value) || isEmptyValue(value)) {
			return;
		}
		conditions.add(ScholarshipCondition.builder()
			.scholarship(scholarship)
			.conditionType(conditionType)
			.operator(operator)
			.valueInt(valueInt)
			.valueIntMax(valueIntMax)
			.valueString(cleanText(value))
			.autoExtracted(false)
			.build());
	}

	private String buildDescription(JsonNode item) {
		List<String> sections = new ArrayList<>();
		addSection(sections, "선발방법", readText(item, "선발방법 상세내용"));
		addSection(sections, "성적기준", readText(item, "성적기준 상세내용"));
		addSection(sections, "소득기준", readText(item, "소득기준 상세내용"));
		addSection(sections, "자격제한", readText(item, "자격제한 상세내용"));
		addSection(sections, "지역거주여부", readText(item, "지역거주여부 상세내용"));
		addSection(sections, "특정자격", readText(item, "특정자격 상세내용"));
		return String.join("\n\n", sections);
	}

	private void addSection(List<String> sections, String title, String value) {
		if (StringUtils.hasText(value) && !isEmptyValue(value)) {
			sections.add("[" + title + "]\n" + cleanText(value));
		}
	}

	private RecruitmentStatus resolveRecruitmentStatus(LocalDateTime startAt, LocalDateTime endAt) {
		LocalDateTime now = LocalDateTime.now();
		if (startAt != null && now.isBefore(startAt)) {
			return RecruitmentStatus.UPCOMING;
		}
		if (endAt != null && now.isAfter(endAt)) {
			return RecruitmentStatus.CLOSED;
		}
		return RecruitmentStatus.OPEN;
	}

	private LocalDateTime readDate(JsonNode item, String fieldName) {
		String value = readText(item, fieldName);
		if (!StringUtils.hasText(value)) {
			return null;
		}

		for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.BASIC_ISO_DATE)) {
			try {
				return LocalDate.parse(value, formatter).atStartOfDay();
			} catch (DateTimeParseException ignored) {
			}
		}
		return null;
	}

	private String readText(JsonNode item, String fieldName) {
		JsonNode value = item.get(fieldName);
		if (value == null || value.isNull()) {
			return null;
		}
		String text = value.asText();
		return StringUtils.hasText(text) ? text.trim() : null;
	}

	private String defaultText(String value, String defaultValue) {
		return StringUtils.hasText(value) ? value : defaultValue;
	}

	private Integer parseFirstInteger(String value) {
		if (!StringUtils.hasText(value) || value.contains("기관확인필요") || isEmptyValue(value)) {
			return null;
		}
		String digits = value.replaceAll("[^0-9]", " ").trim();
		if (!StringUtils.hasText(digits)) {
			return null;
		}
		try {
			return Integer.parseInt(digits.split("\\s+")[0]);
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private Integer parseInteger(String value, Pattern pattern) {
		if (!StringUtils.hasText(value) || value.contains("기관확인필요") || isEmptyValue(value)) {
			return null;
		}
		Matcher matcher = pattern.matcher(value);
		if (!matcher.find()) {
			return null;
		}
		try {
			return Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private Integer parseScaledDecimal(String value, Pattern pattern) {
		if (!StringUtils.hasText(value) || value.contains("기관확인필요") || isEmptyValue(value)) {
			return null;
		}
		Matcher matcher = pattern.matcher(value);
		if (!matcher.find()) {
			return null;
		}
		try {
			String matchedValue = firstMatchedGroup(matcher);
			if (!StringUtils.hasText(matchedValue)) {
				return null;
			}
			double number = Double.parseDouble(matchedValue);
			return (int) Math.round(number * 100);
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private String firstMatchedGroup(Matcher matcher) {
		for (int i = 1; i <= matcher.groupCount(); i++) {
			String group = matcher.group(i);
			if (StringUtils.hasText(group)) {
				return group;
			}
		}
		return null;
	}

	private ConditionOperator resolveRangeOperator(Integer minValue, Integer maxValue) {
		if (minValue == null) {
			return ConditionOperator.EQ;
		}
		if (maxValue != null && !minValue.equals(maxValue)) {
			return ConditionOperator.BETWEEN;
		}
		return ConditionOperator.EQ;
	}

	private Long parseAmount(String value) {
		if (!StringUtils.hasText(value) || value.contains("기관확인필요") || isEmptyValue(value)) {
			return null;
		}

		String compact = value.replace(",", "").replaceAll("\\s+", "");
		java.util.regex.Matcher matcher = java.util.regex.Pattern
			.compile("(\\d+)(억|만원|천원|원)")
			.matcher(compact);

		Long maxAmount = null;
		while (matcher.find()) {
			long number = Long.parseLong(matcher.group(1));
			String unit = matcher.group(2);
			long amount = convertAmount(number, unit);
			maxAmount = maxAmount == null ? amount : Math.max(maxAmount, amount);
		}
		return maxAmount;
	}

	private long convertAmount(long number, String unit) {
		if ("억".equals(unit)) {
			return number * 100_000_000L;
		}
		if ("만원".equals(unit)) {
			return number * 10_000L;
		}
		if ("천원".equals(unit)) {
			return number * 1_000L;
		}
		return number;
	}

	private String cleanText(String value) {
		if (!StringUtils.hasText(value)) {
			return value;
		}
		return value
			.replaceAll("\\s+", " ")
			.replace(" ※", "※")
			.trim();
	}

	private String limit(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}

	private boolean isEmptyValue(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.equals("해당없음") || trimmed.equals("없음") || trimmed.equals("-");
	}

	private boolean isEssayDocument(String name) {
		return name.contains("자기소개")
			|| name.contains("자소서")
			|| name.contains("학업계획")
			|| name.contains("에세이")
			|| name.contains("essay");
	}

	private String normalizeForKey(String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		return value.replaceAll("\\s+", " ").trim();
	}

	private String sha256(String value) {
		try {
			MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
			byte[] digest = messageDigest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder();
			for (byte b : digest) {
				result.append(String.format("%02x", b));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is not available.", exception);
		}
	}
}
