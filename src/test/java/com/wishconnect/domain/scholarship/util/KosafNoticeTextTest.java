package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 공공데이터를 공고 본문처럼 이어 붙이는 규칙.
 *
 * <p>고정하려는 성질은 둘이다. <b>빈칸의 다른 표기를 값으로 넘기지 않는다</b>, 그리고
 * <b>이미 정확한 필드(제목·기간·금액)는 본문에 넣지 않는다.</b> 전자를 어기면 모델이
 * "해당없음" 을 조건으로 만들고, 후자를 어기면 멀쩡한 값을 추측으로 덮어쓴다.
 */
class KosafNoticeTextTest {

	private Map<String, Object> raw(String... pairs) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}
		return map;
	}

	@Test
	@DisplayName("자격 텍스트를 라벨과 함께 본문으로 만든다")
	void buildsBodyWithLabels() {
		String body = KosafNoticeText.bodyOf(raw(
				"지역거주여부 상세내용", "○ 경기도에 주민등록상 2025.04.01 이전부터 계속 거주하는 도민",
				"자격제한 상세내용", "○ 타 지자체 동일사업 지원자는 중복 지원되지 않음"));

		assertThat(body)
				.contains("거주 요건 : ○ 경기도에 주민등록상")
				.contains("지원 제한 : ○ 타 지자체 동일사업");
	}

	@Test
	@DisplayName("'해당없음'·'기관확인필요'는 값이 아니라 빈칸이다")
	void treatsPlaceholdersAsEmpty() {
		String body = KosafNoticeText.bodyOf(raw(
				"성적기준 상세내용", "해당없음",
				"선발인원 상세내용", "※ 기관확인필요",
				"특정자격 상세내용", "해당 없음"));

		assertThat(body).isEmpty();
	}

	@Test
	@DisplayName("'첨부파일 참고'만 있는 칸도 값이 아니다")
	void treatsSeeAttachmentAsEmpty() {
		String body = KosafNoticeText.bodyOf(raw(
				"제출서류 상세내용", "※ 자세한 사항은 첨부파일 또는 홈페이지 참고"));

		assertThat(body).isEmpty();
	}

	@Test
	@DisplayName("제목·기간·금액은 본문에 넣지 않는다 — 이미 정확한 값을 덮어쓰지 않기 위해서다")
	void neverIncludesAlreadyStructuredFields() {
		String body = KosafNoticeText.bodyOf(raw(
				"상품명", "학자금대출장기연체자신용회복",
				"모집시작일", "2026-04-01",
				"모집종료일", "2026-12-11",
				"운영기관명", "경기도청",
				"소득기준 상세내용", "○ 학자금 지원구간 8구간 이내"));

		assertThat(body).contains("소득 기준 : ○ 학자금 지원구간 8구간 이내");
		assertThat(body)
				.doesNotContain("2026-12-11")
				.doesNotContain("학자금대출장기연체자신용회복")
				.doesNotContain("경기도청");
	}

	@Test
	@DisplayName("상품명은 제목으로 따로 넘긴다")
	void takesProductNameAsTitle() {
		assertThat(KosafNoticeText.titleOf(raw("상품명", "학자금대출장기연체자신용회복")))
				.isEqualTo("학자금대출장기연체자신용회복");
	}
}
