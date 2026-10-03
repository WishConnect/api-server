package com.wishconnect.domain.scholarship.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * 감사 로그 스냅샷(JSON)을 필드 단위로 비교한다. 복구 미리보기·필드 선택 복구·변경 요약이 같은 기준을 쓴다.
 *
 * <p>스냅샷은 두 가지 모양이다.
 * <ul>
 *   <li>{@link Kind#ADMIN_SNAPSHOT} — 수기 수정·내리기({@code ScholarshipAdminSnapshot}). 장학금 필드가 평평하게 있다.</li>
 *   <li>{@link Kind#AGGREGATE} — 통합 수정({@code AdminScholarshipEditSnapshot}).
 *       {@code scholarship} 객체 + {@code conditions}·{@code documents} 배열.</li>
 * </ul>
 *
 * <p>비교 전에 정규화한다: 날짜는 초 단위로 자르고(저장 정밀도 차이로 "바뀐 것처럼" 보이는 것을 막는다),
 * 조건·서류는 행 ID 를 뺀다(통합 수정은 조건을 지우고 다시 만들어 ID 가 매번 바뀐다).
 */
public final class ScholarshipChangeFields {

	public enum Kind { ADMIN_SNAPSHOT, AGGREGATE }

	/** 모집 상태. 복구 화면에서 기본으로 선택하지 않는다(마감 처리를 되돌리는 사고가 있었다). */
	public static final String RECRUITMENT_STATUS = "recruitmentStatus";
	/** 내리기 상태. ADMIN_SNAPSHOT 의 deletedAt 을 "삭제됨 여부" 로 본다. */
	public static final String DELETED = "deleted";
	public static final String CONDITIONS = "conditions";
	public static final String DOCUMENTS = "documents";

	private static final Map<String, String> LABELS = new LinkedHashMap<>();
	private static final Set<String> DATE_FIELDS = Set.of("applicationStartAt", "applicationEndAt", "deletedAt");
	private static final DateTimeFormatter SECONDS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

	static {
		LABELS.put("title", "제목");
		LABELS.put("provider", "기관");
		LABELS.put("summary", "요약");
		LABELS.put("description", "설명");
		LABELS.put("scholarshipType", "유형");
		LABELS.put(RECRUITMENT_STATUS, "모집 상태");
		LABELS.put("applicationStartAt", "모집 시작");
		LABELS.put("applicationEndAt", "모집 마감");
		LABELS.put("selectionCount", "선발 인원");
		LABELS.put("amount", "금액");
		LABELS.put("homepageUrl", "홈페이지");
		LABELS.put("detailUrl", "상세 URL");
		LABELS.put("noticeKind", "공지 종류");
		LABELS.put("combined", "통합 공고");
		LABELS.put("submissionMethod", "제출 방법");
		LABELS.put("submissionChannel", "제출 경로");
		LABELS.put("submissionEvidence", "제출 근거");
		LABELS.put("contact", "문의처");
		LABELS.put("essayRequirement", "자소서 필요");
		LABELS.put("essayEvidence", "자소서 근거");
		LABELS.put("interviewRequirement", "면접 필요");
		LABELS.put("interviewEvidence", "면접 근거");
		LABELS.put("verified", "검수 완료");
		LABELS.put(DELETED, "내리기(삭제) 상태");
		LABELS.put(CONDITIONS, "자격·우대 조건");
		LABELS.put(DOCUMENTS, "제출 서류");
	}

	private static final List<String> ADMIN_FIELDS = List.of("title", "provider", "summary", "description",
			"scholarshipType", RECRUITMENT_STATUS, "applicationStartAt", "applicationEndAt", "selectionCount",
			"amount", "homepageUrl", "verified", DELETED);

	private static final List<String> AGGREGATE_FIELDS = List.of("title", "provider", "summary", "description",
			"scholarshipType", RECRUITMENT_STATUS, "applicationStartAt", "applicationEndAt", "selectionCount",
			"amount", "homepageUrl", "detailUrl", "noticeKind", "combined", "submissionMethod",
			"submissionChannel", "submissionEvidence", "contact", "essayRequirement", "essayEvidence",
			"interviewRequirement", "interviewEvidence", CONDITIONS, DOCUMENTS);

	private ScholarshipChangeFields() {
	}

	public static Kind kindOf(JsonNode snapshot) {
		return snapshot != null && snapshot.has("scholarship") ? Kind.AGGREGATE : Kind.ADMIN_SNAPSHOT;
	}

	public static List<String> fields(Kind kind) {
		return kind == Kind.AGGREGATE ? AGGREGATE_FIELDS : ADMIN_FIELDS;
	}

	public static String label(String field) {
		return LABELS.getOrDefault(field, field);
	}

	/** 비교·표시용으로 정규화한 값. 없으면 NullNode. */
	public static JsonNode value(Kind kind, JsonNode snapshot, String field) {
		if (snapshot == null || snapshot.isNull()) {
			return NullNode.getInstance();
		}
		if (kind == Kind.ADMIN_SNAPSHOT) {
			if (DELETED.equals(field)) {
				JsonNode deletedAt = snapshot.get("deletedAt");
				return BooleanNode.valueOf(deletedAt != null && !deletedAt.isNull());
			}
			return normalize(field, snapshot.get(field));
		}
		if (CONDITIONS.equals(field) || DOCUMENTS.equals(field)) {
			return withoutIds(snapshot.get(field));
		}
		JsonNode scholarship = snapshot.get("scholarship");
		return normalize(field, scholarship == null ? null : scholarship.get(field));
	}

	/** {@code before} 와 {@code after} 사이에서 바뀐 필드(카탈로그 순서). */
	public static List<String> changed(Kind kind, JsonNode before, JsonNode after) {
		List<String> changed = new ArrayList<>();
		for (String field : fields(kind)) {
			if (!value(kind, before, field).equals(value(kind, after, field))) {
				changed.add(field);
			}
		}
		return changed;
	}

	/**
	 * 감사 로그 detail 에 붙일 한 줄 요약. 예: "변경: 제목, 모집 상태(OPEN→CLOSED), 자격·우대 조건(3→2건)".
	 * 값 전체는 before_json/after_json 에 남으므로 여기서는 무엇이 바뀌었는지만 보이게 한다.
	 */
	public static String summarize(Kind kind, JsonNode before, JsonNode after) {
		List<String> parts = new ArrayList<>();
		for (String field : changed(kind, before, after)) {
			JsonNode from = value(kind, before, field);
			JsonNode to = value(kind, after, field);
			if (from.isArray() || to.isArray()) {
				parts.add(label(field) + "(" + from.size() + "→" + to.size() + "건)");
			} else if (RECRUITMENT_STATUS.equals(field) || DELETED.equals(field) || field.endsWith("Requirement")
					|| "scholarshipType".equals(field)) {
				parts.add(label(field) + "(" + text(from) + "→" + text(to) + ")");
			} else {
				parts.add(label(field));
			}
		}
		return parts.isEmpty() ? "변경 없음" : "변경: " + String.join(", ", parts);
	}

	private static String text(JsonNode value) {
		return value == null || value.isNull() ? "없음" : value.asText();
	}

	private static JsonNode normalize(String field, JsonNode value) {
		if (value == null || value.isNull() || value.isMissingNode()) {
			return NullNode.getInstance();
		}
		if (DATE_FIELDS.contains(field) && value.isTextual()) {
			try {
				return TextNode.valueOf(LocalDateTime.parse(value.asText()).truncatedTo(ChronoUnit.SECONDS)
						.format(SECONDS));
			} catch (DateTimeParseException ignored) {
				return value;
			}
		}
		if (value.isTextual() && value.asText().isEmpty()) {
			// 빈 문자열과 null 은 화면에서 구분되지 않는다. 같은 값으로 본다.
			return NullNode.getInstance();
		}
		return value;
	}

	private static JsonNode withoutIds(JsonNode array) {
		ArrayNode result = JsonNodeFactory.instance.arrayNode();
		if (array == null || !array.isArray()) {
			return result;
		}
		for (JsonNode element : array) {
			if (!element.isObject()) {
				result.add(element);
				continue;
			}
			ObjectNode copy = element.deepCopy();
			copy.remove("id");
			JsonNode refs = copy.get("refs");
			if (refs != null && refs.isArray()) {
				// 참조는 집합이라 순서가 매번 다를 수 있다.
				ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
				StreamSupport.stream(refs.spliterator(), false)
						.sorted(Comparator.comparing(JsonNode::toString))
						.forEach(sorted::add);
				copy.set("refs", sorted);
			}
			result.add(copy);
		}
		return result;
	}
}
