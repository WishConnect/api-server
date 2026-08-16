package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 공공데이터 분류 필드에서 태그를 뽑는 규칙.
 *
 * <p>지금까지 응답의 tags 는 전 건 빈 배열이었다. 원문에 분류 정보가 있는데도 쓰지 않았던 것이라,
 * 여기서는 "원문 표기를 실제로 잡아내는지"를 고정한다.
 */
class ScholarshipTagExtractorTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private List<String> extract(Map<String, String> fields) {
		ObjectNode node = MAPPER.createObjectNode();
		fields.forEach(node::put);
		return ScholarshipTagExtractor.extract(node, ScholarshipTagExtractorTest::read);
	}

	private static String read(JsonNode item, String field) {
		JsonNode value = item.get(field);
		return value == null || value.isNull() ? null : value.asText();
	}

	/** 다중 선택 값이 구분자 없이 이어붙어 온다. 정규식으로는 못 자르고 어휘로 훑어야 한다. */
	@Test
	@DisplayName("구분자 없이 붙어 온 학자금유형에서 값을 골라낸다")
	void splitsConcatenatedAidTypes() {
		assertThat(extract(Map.of("학자금유형구분", "성적우수특기자")))
				.contains("성적우수", "특기자");
	}

	@Test
	@DisplayName("'해당없음'·'기타'는 태그로 만들지 않는다")
	void skipsNoiseValues() {
		assertThat(extract(Map.of("학자금유형구분", "해당없음", "운영기관구분", "기타"))).isEmpty();
	}

	/** 전 계열 대상이면 계열 태그가 변별력이 없다. */
	@Test
	@DisplayName("학과 계열이 전부 선택되면 계열 태그를 만들지 않는다")
	void skipsMajorFieldsWhenAllSelected() {
		String all = "공학계열교육계열사회계열예체능계열의약계열인문계열자연계열제한없음";
		assertThat(extract(Map.of("학과구분", all))).isEmpty();
	}

	@Test
	@DisplayName("일부 계열만 선택되면 그 계열을 태그로 만든다")
	void keepsMajorFieldsWhenPartial() {
		assertThat(extract(Map.of("학과구분", "공학계열자연계열")))
				.containsExactlyInAnyOrder("공학계열", "자연계열");
	}

	@Test
	@DisplayName("운영기관구분의 괄호 뒷부분은 떼고 태그로 쓴다")
	void trimsParenthesisFromOrgType() {
		assertThat(extract(Map.of("운영기관구분", "지자체(출자출연기관)"))).containsExactly("지자체");
	}

	@Test
	@DisplayName("상품구분이 학자금이면 장학금과 구분되도록 태그를 단다")
	void marksNonScholarshipProduct() {
		assertThat(extract(Map.of("상품구분", "학자금"))).contains("학자금");
		assertThat(extract(Map.of("상품구분", "장학금"))).doesNotContain("학자금");
	}

	@Test
	@DisplayName("지원 시 걸리는 조건은 미리 알 수 있게 태그로 만든다")
	void marksApplicationConstraints() {
		assertThat(extract(Map.of(
				"추천필요여부 상세내용", "소속 대학 총장 추천 필요",
				"지역거주여부 상세내용", "부산 지역 3년 이상 거주")))
				.contains("추천서 필요", "지역거주 조건");
	}

	@Test
	@DisplayName("조건 칸이 '해당없음'이면 조건 태그를 달지 않는다")
	void noConstraintTagWhenNotApplicable() {
		assertThat(extract(Map.of("추천필요여부 상세내용", "해당없음")))
				.doesNotContain("추천서 필요");
	}

	@Test
	@DisplayName("같은 태그가 여러 필드에서 나와도 한 번만 넣는다")
	void deduplicates() {
		List<String> tags = extract(Map.of("학자금유형구분", "성적우수성적우수"));
		assertThat(tags).containsExactly("성적우수");
	}

	@Test
	@DisplayName("필드가 아예 없으면 빈 목록을 준다")
	void emptyWhenNoFields() {
		assertThat(extract(Map.of())).isEmpty();
	}
}
