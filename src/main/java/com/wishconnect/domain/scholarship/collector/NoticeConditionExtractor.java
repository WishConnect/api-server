package com.wishconnect.domain.scholarship.collector;

import com.wishconnect.domain.scholarship.entity.ConditionType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 대학 장학공지 본문에서 지원 자격 조건 문장을 뽑아낸다.
 *
 * <p>공공데이터(KOSAF)는 "소득기준 상세내용" 같은 정형 필드가 있어 그대로 조건이 되지만,
 * 대학 공지는 자유 서술이라 조건이 아예 만들어지지 않았다. 그 결과 대학 수집분은 매칭 점수가
 * 항상 0점이었다. 여기서는 본문을 문장 단위로 훑어 조건이 담긴 문장만 골라낸다.
 *
 * <p>뽑아낸 문장은 {@code valueString} 으로 저장되고, 숫자 구조화(valueInt)는 기존
 * ConditionRuleParser / LLM 추출 파이프라인이 이어서 처리한다. 즉 여기서는 "어느 문장이
 * 어떤 조건인지"만 판별하며, 확신이 없으면 만들지 않는다(잘못된 조건은 탈락 사유가 되어
 * 추천에서 부당하게 제외시키므로, 놓치는 쪽이 안전하다).
 */
public final class NoticeConditionExtractor {

	/** 문장이 지나치게 길면 조건과 무관한 내용이 섞이므로 잘라 보관한다. */
	private static final int MAX_SNIPPET_LENGTH = 300;
	/** 본문 앞부분에 자격 요건이 몰려 있어, 지나치게 뒤쪽(첨부·문의처 등)은 보지 않는다. */
	private static final int MAX_SCAN_LENGTH = 4000;

	/** 소득분위/학자금 지원구간. 예: "소득 8분위 이하", "학자금 지원구간 4구간 이내" */
	private static final Pattern INCOME = Pattern.compile(
			"(소득|가구|학자금\\s*지원)[^.\\n]{0,20}\\d{1,2}\\s*(분위|구간)");

	/** 성적 기준. 예: "직전학기 평점평균 3.0 이상", "성적 80점 이상" */
	private static final Pattern ACADEMIC = Pattern.compile(
			"(평점|성적|학점|백분위)[^.\\n]{0,20}\\d[.,]?\\d*\\s*(점)?\\s*이상");

	/** 학년/학기 기준. 예: "2학년 이상 재학생", "대학 3학기~7학기" */
	private static final Pattern GRADE = Pattern.compile(
			"\\d\\s*(학년|학기)[^.\\n]{0,10}(이상|이내|이하|~|-|부터)|\\d\\s*(학년|학기)\\s*(재학생|재학)");

	/** 거주지 요건. 예: "부산광역시에 거주하는", "전라북도 출신" */
	private static final Pattern REGION = Pattern.compile(
			"(특별시|광역시|특별자치시|특별자치도|[가-힣]{2,4}(도|시|군|구))[^.\\n]{0,15}(거주|출신|소재)");

	private static final Map<ConditionType, Pattern> PATTERNS = new EnumMap<>(Map.of(
			ConditionType.INCOME_CRITERIA, INCOME,
			ConditionType.ACADEMIC_CRITERIA, ACADEMIC,
			ConditionType.GRADE_LEVEL, GRADE,
			ConditionType.REGION_RESIDENCY, REGION));

	/**
	 * 자격 요건이 아니라 지급·결과 안내에 붙는 문장을 걸러낸다.
	 * (예: "성적 우수자에게 지급합니다", "선발 결과는 3.0 이상자를 대상으로 발표")
	 */
	private static final Pattern NON_REQUIREMENT = Pattern.compile("(지급일|지급 예정|계좌|문의|발표|결과)");

	private NoticeConditionExtractor() {
	}

	/**
	 * @param bodyText 공지 본문 텍스트
	 * @return 조건 유형별로 최대 1건씩 추출한 원문 문장. 조건이 없으면 빈 목록.
	 */
	public static List<Extracted> extract(String bodyText) {
		if (bodyText == null || bodyText.isBlank()) {
			return List.of();
		}
		String scanned = bodyText.length() > MAX_SCAN_LENGTH
				? bodyText.substring(0, MAX_SCAN_LENGTH) : bodyText;

		Map<ConditionType, String> found = new EnumMap<>(ConditionType.class);
		for (String sentence : splitSentences(scanned)) {
			if (NON_REQUIREMENT.matcher(sentence).find()) {
				continue;
			}
			for (Map.Entry<ConditionType, Pattern> entry : PATTERNS.entrySet()) {
				if (found.containsKey(entry.getKey())) {
					continue;
				}
				Matcher matcher = entry.getValue().matcher(sentence);
				if (matcher.find()) {
					found.put(entry.getKey(), trim(sentence));
				}
			}
		}

		List<Extracted> result = new ArrayList<>();
		found.forEach((type, snippet) -> result.add(new Extracted(type, snippet)));
		return result;
	}

	private static List<String> splitSentences(String text) {
		List<String> sentences = new ArrayList<>();
		for (String chunk : text.split("[\\n\\r]+|(?<=[.!?])\\s+|[·•▶■○]")) {
			String trimmed = chunk.trim();
			if (trimmed.length() >= 5) {
				sentences.add(trimmed);
			}
		}
		return sentences;
	}

	private static String trim(String sentence) {
		String normalized = sentence.replaceAll("\\s+", " ").trim();
		return normalized.length() > MAX_SNIPPET_LENGTH
				? normalized.substring(0, MAX_SNIPPET_LENGTH) : normalized;
	}

	/**
	 * @param type    판별된 조건 유형
	 * @param snippet 근거가 된 본문 원문(사람이 검증할 수 있도록 보존)
	 */
	public record Extracted(ConditionType type, String snippet) {
	}
}
