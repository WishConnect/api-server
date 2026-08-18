package com.wishconnect.domain.scholarship.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공공데이터(KOSAF) 응답을 <b>공고 본문처럼</b> 이어 붙인다.
 *
 * <p>응답이 구조화돼 있다고 보고 LLM 을 태우지 않았는데, 정작 자격 요건은 전부 자유 텍스트다.
 *
 * <pre>
 *   지역거주여부 상세내용  "경기도에 주민등록상 2025.04.01 이전부터 계속 거주하는 도민"
 *   자격제한 상세내용     "타 지자체/기관 동일사업 지원자는 중복 지원되지 않으며…"
 * </pre>
 *
 * <p>대학 공지 본문과 같은 모양이라 같은 파서로 처리할 수 있다. 다만 <b>제목·기간·금액은
 * 이미 정확한 구조화 필드로 들어와 있으므로 여기에 넣지 않는다.</b> LLM 이 그걸 다시 추측해
 * 멀쩡한 값을 덮어쓰면 손해만 본다.
 *
 * <p>"해당없음"·"기관확인필요"·"홈페이지 참고" 는 값이 아니라 <b>빈칸의 다른 표기</b>다.
 * 그대로 넘기면 모델이 그것을 조건으로 만들어낸다.
 */
public final class KosafNoticeText {

	private KosafNoticeText() {
	}

	/** 조건·서류 판단에 쓰는 필드만 고른다. 라벨을 살려야 모델이 무엇을 읽는지 안다. */
	private static final Map<String, String> FIELDS = new LinkedHashMap<>() {{
		put("대학구분", "대학 구분");
		put("학과구분", "학과 구분");
		put("학년구분", "학년 구분");
		put("성적기준 상세내용", "성적 기준");
		put("소득기준 상세내용", "소득 기준");
		put("지역거주여부 상세내용", "거주 요건");
		put("특정자격 상세내용", "특정 자격");
		put("자격제한 상세내용", "지원 제한");
		put("추천필요여부 상세내용", "추천 필요 여부");
		put("선발방법 상세내용", "선발 방법");
		put("제출서류 상세내용", "제출 서류");
		put("지원내역 상세내용", "지원 내역");
	}};

	/** 값이 아니라 "없음"을 뜻하는 표기들. 그대로 넘기면 모델이 조건으로 만든다. */
	private static final List<String> BLANKS = List.of(
			"해당없음", "해당 없음", "없음", "기관확인필요", "기관 확인 필요", "-");

	/**
	 * @return 파서에 넘길 본문. 쓸 만한 필드가 하나도 없으면 빈 문자열
	 */
	public static String bodyOf(Map<String, Object> raw) {
		if (raw == null || raw.isEmpty()) {
			return "";
		}
		StringBuilder body = new StringBuilder();
		for (Map.Entry<String, String> field : FIELDS.entrySet()) {
			Object value0 = raw.get(field.getKey());
			String value = clean(value0 == null ? null : String.valueOf(value0));
			if (value != null) {
				body.append(field.getValue()).append(" : ").append(value).append('\n');
			}
		}
		return body.toString().trim();
	}

	/** 공고 제목 자리. 상품명이 곧 장학금 이름이다. */
	public static String titleOf(Map<String, Object> raw) {
		if (raw == null) {
			return null;
		}
		Object name = raw.get("상품명");
		return name == null ? null : clean(String.valueOf(name));
	}

	private static String clean(String value) {
		if (value == null || value.isBlank() || "null".equals(value)) {
			return null;
		}
		String trimmed = value.replaceAll("\\s+", " ").trim();
		for (String blank : BLANKS) {
			if (trimmed.equals(blank)) {
				return null;
			}
		}
		// "※ 자세한 사항은 첨부파일 또는 홈페이지 참고" 처럼 안내만 있는 칸도 값이 아니다.
		String withoutMark = trimmed.replaceAll("^[※○▶\\-\\s]+", "");
		if (withoutMark.matches("자세한 사항은.*(참고|참조)\\.?") || withoutMark.length() < 2) {
			return null;
		}
		return trimmed;
	}
}
