package com.wishconnect.domain.scholarship.util;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.util.StringUtils;

/*
공공데이터 원문의 분류 필드에서 사용자에게 보여줄 태그를 뽑는다.

왜 필요한가: 지금까지 응답의 tags 는 전 건 빈 배열이었다(서비스 코드에 List.of() 하드코딩).
원문에는 분류 정보가 들어 있는데 저장·노출을 안 하고 있었을 뿐이다.

어려운 점: 다중 선택 필드가 구분자 없이 이어붙어 온다.
  학과구분 = "공학계열교육계열사회계열예체능계열의약계열인문계열자연계열제한없음"
그래서 정규식으로는 못 자르고, 알려진 어휘를 훑어 포함 여부로 판정한다.

"해당없음"·"기타"·"제한없음" 은 태그로 만들지 않는다. 정보가 없다는 뜻이라 붙여봐야 노이즈다.
계열이 전부 선택된 경우(= 전 계열 대상)도 변별력이 없어 뺀다.
 */
public final class ScholarshipTagExtractor {

	/** 태그로 만들지 않는 값. "정보 없음"을 뜻해 붙이면 노이즈만 된다. */
	private static final Set<String> NOISE = Set.of("해당없음", "기타", "제한없음", "-", "");

	/**
	 * 장학금 성격. 사용자가 가장 먼저 훑는 정보라 순서를 앞에 둔다.
	 * 원문 실측값: 지역연고 / 기타 / 해당없음 / 성적우수 / 특기자 / 소득구분 / 장애인
	 */
	private static final List<String> AID_TYPES =
			List.of("지역연고", "성적우수", "특기자", "소득구분", "장애인");

	/** 학과 계열. 전부 선택되면 "전 계열"이라 태그를 만들지 않는다. */
	private static final List<String> MAJOR_FIELDS =
			List.of("인문계열", "사회계열", "교육계열", "공학계열", "자연계열", "의약계열", "예체능계열");

	private ScholarshipTagExtractor() {
	}

	/**
	 * 우선순위 순으로 태그를 만든다. 중복은 제거하고 입력 순서를 유지한다.
	 *
	 * @param reader 원문 필드를 읽는 함수(매퍼의 readText 를 그대로 넘긴다)
	 */
	public static List<String> extract(JsonNode item, FieldReader reader) {
		Set<String> tags = new LinkedHashSet<>();

		// 1. 장학금 성격 (지역연고·성적우수 등)
		addContained(tags, reader.read(item, "학자금유형구분"), AID_TYPES);

		// 2. 장학금 / 학자금 구분. '학자금'은 성격이 달라 구분해서 보여줄 값이 있다.
		String product = clean(reader.read(item, "상품구분"));
		if ("학자금".equals(product)) {
			tags.add("학자금");
		}

		// 3. 운영 주체. "지자체(출자출연기관)" 처럼 괄호가 붙어 와서 앞부분만 쓴다.
		String orgType = clean(reader.read(item, "운영기관구분"));
		if (StringUtils.hasText(orgType) && !NOISE.contains(orgType)) {
			tags.add(orgType.split("\\(")[0].trim());
		}

		// 4. 학과 계열. 전 계열이면 변별력이 없어 뺀다.
		List<String> fields = containedValues(reader.read(item, "학과구분"), MAJOR_FIELDS);
		if (!fields.isEmpty() && fields.size() < MAJOR_FIELDS.size()) {
			tags.addAll(fields);
		}
		if (contains(reader.read(item, "학과구분"), "특정학과")) {
			tags.add("특정학과");
		}

		// 5. 지원 시 걸리는 조건. 사용자가 미리 알아야 준비할 수 있다.
		if (hasRealValue(reader.read(item, "추천필요여부 상세내용"))) {
			tags.add("추천서 필요");
		}
		if (hasRealValue(reader.read(item, "지역거주여부 상세내용"))) {
			tags.add("지역거주 조건");
		}

		tags.remove(null);
		tags.removeIf(tag -> !StringUtils.hasText(tag) || NOISE.contains(tag));
		return new ArrayList<>(tags);
	}

	private static void addContained(Set<String> tags, String source, List<String> vocabulary) {
		tags.addAll(containedValues(source, vocabulary));
	}

	/** 구분자 없이 이어붙은 문자열에서 어휘에 있는 값만 골라낸다. */
	private static List<String> containedValues(String source, List<String> vocabulary) {
		if (!StringUtils.hasText(source)) {
			return List.of();
		}
		List<String> found = new ArrayList<>();
		for (String word : vocabulary) {
			if (source.contains(word)) {
				found.add(word);
			}
		}
		return found;
	}

	private static boolean contains(String source, String word) {
		return StringUtils.hasText(source) && source.contains(word);
	}

	/** "해당없음" 류가 아닌 실제 내용이 들어 있는지. */
	private static boolean hasRealValue(String source) {
		String value = clean(source);
		return StringUtils.hasText(value) && !NOISE.contains(value);
	}

	private static String clean(String value) {
		return value == null ? null : value.trim();
	}

	/** 매퍼의 readText 를 그대로 재사용하기 위한 함수형 인터페이스. */
	@FunctionalInterface
	public interface FieldReader {
		String read(JsonNode item, String fieldName);
	}
}
