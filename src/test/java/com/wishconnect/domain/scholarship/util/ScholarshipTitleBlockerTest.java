package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 중복 후보 좁히기(blocking) 검증.
 *
 * <p>제목은 실제 수집 데이터에서 가져왔다. 같은 장학금이 회차·캠퍼스만 바꿔 다시 올라오는
 * 패턴을 같은 키로 묶어야 하고, 서로 다른 장학금은 갈라야 한다.
 */
class ScholarshipTitleBlockerTest {

	@Test
	@DisplayName("학년도·학기·차수만 다른 같은 장학금은 같은 키로 묶인다")
	void groupsSameScholarshipAcrossTerms() {
		String a = ScholarshipTitleBlocker.blockingKey("2026학년도 2학기 국가장학금 2차 신청 안내");
		String b = ScholarshipTitleBlocker.blockingKey("2026학년도 1학기 국가장학금 1차 신청 안내");

		assertThat(a).isEqualTo(b).isEqualTo("국가장학금");
	}

	@Test
	@DisplayName("캠퍼스 표기와 괄호 주석이 달라도 같은 키로 묶인다")
	void ignoresCampusAndBrackets() {
		String a = ScholarshipTitleBlocker.blockingKey("[서울C] 2026학년도 2학기 운연장학 신청 안내(~8/21까지)");
		String b = ScholarshipTitleBlocker.blockingKey("(국제) 2026학년도 운연장학 선발 공고");

		assertThat(a).isEqualTo(b).isEqualTo("운연장학");
	}

	@Test
	@DisplayName("서로 다른 장학금은 다른 키가 된다")
	void separatesDifferentScholarships() {
		String a = ScholarshipTitleBlocker.blockingKey("2026학년도 2학기 국가장학금 신청 안내");
		String b = ScholarshipTitleBlocker.blockingKey("2026학년도 2학기 주거안정장학금 신청 안내");
		String c = ScholarshipTitleBlocker.blockingKey("서암윤세영재단 윤세영 스칼라십 선발 안내");

		assertThat(a).isNotEqualTo(b);
		assertThat(a).isNotEqualTo(c);
		assertThat(b).isNotEqualTo(c);
	}

	@Test
	@DisplayName("근로장학은 회차가 달라도 묶이지만 인턴장학과는 갈린다")
	void groupsWorkStudyByProgram() {
		String a = ScholarshipTitleBlocker.blockingKey("(통합) 2026학년도 2학기 2차 국가근로장학금 신청 안내");
		String b = ScholarshipTitleBlocker.blockingKey("2026년도 2학기 국가근로장학금 1차 신청기간 안내");
		String c = ScholarshipTitleBlocker.blockingKey("2026년도 2학기 경희인턴(교내인턴장학) 모집 안내");

		assertThat(a).isEqualTo(b);
		assertThat(a).isNotEqualTo(c);
	}

	@Test
	@DisplayName("실측: 같은 공고가 학교·재단 양쪽에서 수집된 경우 묶인다")
	void groupsRealDuplicatePair() {
		// 로컬 수집분에서 실제로 중복 수집된 쌍
		String a = ScholarshipTitleBlocker.blockingKey(
				"2026학년도 2학기 주거안정장학금 2차 신청 안내 (한국장학재단 문의)");
		String b = ScholarshipTitleBlocker.blockingKey(
				"[한국장학재단] 2026학년도 2학기 주거안정장학금 2차 신청 안내(8/12~9/9)");

		assertThat(a).isEqualTo(b).isEqualTo("주거안정장학금");
	}

	@Test
	@DisplayName("실측: 캠퍼스만 다른 공고도 후보로 묶인다 — 별개 모집일 수 있어 LLM·사람이 판정한다")
	void groupsCampusVariantsAsCandidate() {
		String a = ScholarshipTitleBlocker.blockingKey("2026-1학기 복지장학금 시행 공고(다빈치캠퍼스)");
		String b = ScholarshipTitleBlocker.blockingKey("2026-1학기 복지장학금 시행 공고(서울캠퍼스)");

		// 같은 키로 묶이지만 이것이 중복 확정을 뜻하지는 않는다.
		// blocking 은 후보를 만드는 단계이고, 판정은 LLM(기간·금액 확인) 과 사람 승인이 한다.
		assertThat(a).isEqualTo(b);
	}

	@Test
	@DisplayName("구별력이 없을 만큼 짧아지면 후보로 묶지 않는다")
	void returnsNullForUnusableKey() {
		// 정규화하면 사실상 아무것도 남지 않는 제목
		assertThat(ScholarshipTitleBlocker.blockingKey("2026학년도 2학기 신청 안내")).isNull();
		assertThat(ScholarshipTitleBlocker.blockingKey("공고")).isNull();
		assertThat(ScholarshipTitleBlocker.blockingKey("")).isNull();
		assertThat(ScholarshipTitleBlocker.blockingKey(null)).isNull();
	}
}
