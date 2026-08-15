package com.wishconnect.domain.scholarship.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 중앙대 수집기의 파싱 규칙 검증.
 *
 * <p>본문 예시는 실제 CAU Notice 공지에서 가져온 표현을 줄인 것이다. 특히 신청기간 추출은
 * 라벨 없이 첫 날짜 범위를 집으면 서류 유효기간·근로기간을 신청기간으로 오인하는 사례가 있어,
 * 그 오탐 케이스를 회귀 테스트로 고정한다.
 */
class CauNoticeCollectorTest {

	// --- 신청기간 추출 ---

	@Test
	@DisplayName("신청기간 라벨 뒤의 날짜 범위를 신청기간으로 잡는다")
	void parsesRangeAfterStrongLabel() {
		String text = "■ 선발 일정 · 신청기간 : 2026. 8. 10(월) ~ 9. 4(금) 17:00 까지 접수 분에 한함"
				+ " · 서류심사 : 2026. 9. 7(월) ~ 9. 18(금)";

		var period = CauNoticeCollector.parsePeriod(text, 2026);

		assertThat(period).isNotNull();
		assertThat(period.start().toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 10));
		assertThat(period.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 4));
		assertThat(period.end().toLocalTime().getHour()).isEqualTo(17);
	}

	@Test
	@DisplayName("서류 유효기간이 신청기간보다 앞에 나와도 신청기간을 잡는다")
	void ignoresDocumentValidityDatesBeforeLabel() {
		// 실제 오탐 사례(BBS_SEQ=30050): 제출서류 표의 회원 검증기간이 본문 앞쪽에 있었다.
		String text = "가족관계증명서 2026.7.29. 이후 발급분 서울런 회원 확인 캡처본 제출 2026.1.1.~7.28. 검증 완료"
				+ " 신청기간 2026. 9. 1.(월) ~ 9. 12.(금)";

		var period = CauNoticeCollector.parsePeriod(text, 2026);

		assertThat(period).isNotNull();
		assertThat(period.start().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 1));
		assertThat(period.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 12));
	}

	@Test
	@DisplayName("근로기간처럼 신청과 무관한 범위만 있으면 기간 없음으로 둔다")
	void returnsNullWhenOnlyUnrelatedRangeExists() {
		// 실제 오탐 사례(BBS_SEQ=29953): 선발 결과 공고의 근로기간을 신청기간으로 잡았었다.
		// 잘못된 종료일은 공고를 마감 처리해 통째로 버리게 하므로, 못 찾으면 비워두는 편이 안전하다.
		String text = "국가근로장학금 사업(집중근로) 개요 1. 근로기간 : 2026. 7. 1.(수) ~ 2026. 8. 31.(월)"
				+ " 2. 근로시간 가. 일 최대 8시간";

		assertThat(CauNoticeCollector.parsePeriod(text, 2026)).isNull();
	}

	@Test
	@DisplayName("표에서 라벨이 '신청' 한 단어로만 붙어도 잡는다")
	void parsesRangeAfterWeakLabel() {
		// 실제 사례(BBS_SEQ=29993): 학자금 대출 일정표의 열 제목이 '신청' 뿐이었다.
		String text = "구분 등록금 대출(일시납) 생활비 대출 신청 7.1.(수) ~ 11.17.(화)";

		var period = CauNoticeCollector.parsePeriod(text, 2026);

		assertThat(period).isNotNull();
		assertThat(period.start().toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 1));
		assertThat(period.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 17));
	}

	@Test
	@DisplayName("성립하지 않는 날짜 후보는 건너뛰고 다음 라벨에서 다시 찾는다")
	void skipsInvalidDateAndRetriesNextLabel() {
		// 실제 사례(BBS_SEQ=29762): 두 자리 연도 '26.3.6. 이 26월로 읽혀 날짜가 되지 않는다.
		// 여기서 멈추면 뒤에 있는 진짜 신청기간을 놓친다.
		String text = "사전신청 기간('26.3.6.~3.25.)에 신청을 완료한 학생은 심사서류를 제출하여 주시기 바랍니다."
				+ " 신청기간 2026. 4. 8.(수) ~ 4. 14.(화) 23시 59분까지";

		var period = CauNoticeCollector.parsePeriod(text, 2026);

		assertThat(period).isNotNull();
		assertThat(period.start().toLocalDate()).isEqualTo(LocalDate.of(2026, 4, 8));
		assertThat(period.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 4, 14));
	}

	@Test
	@DisplayName("마감일만 있는 표기도 잡는다")
	void parsesDeadlineOnly() {
		String text = "신청기한 : 2026. 7. 29.(화) 18:00 까지";

		var period = CauNoticeCollector.parsePeriod(text, 2026);

		assertThat(period).isNotNull();
		assertThat(period.start()).isNull();
		assertThat(period.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 29));
	}

	@Test
	@DisplayName("연도 없는 해 넘김 표기는 종료일을 다음 해로 보정한다")
	void rollsOverYearWhenEndBeforeStart() {
		String text = "신청기간 12. 20. ~ 1. 10.";

		var period = CauNoticeCollector.parsePeriod(text, 2026);

		assertThat(period).isNotNull();
		assertThat(period.start().toLocalDate()).isEqualTo(LocalDate.of(2026, 12, 20));
		assertThat(period.end().toLocalDate()).isEqualTo(LocalDate.of(2027, 1, 10));
	}

	@Test
	@DisplayName("기간 표기가 없으면 null 을 반환한다")
	void returnsNullWhenNoPeriod() {
		assertThat(CauNoticeCollector.parsePeriod("선발자 명단은 포털에서 확인하시기 바랍니다.", 2026)).isNull();
	}

	// --- 기준 연도 ---

	@Test
	@DisplayName("목록의 WRITE_DATE 에서 기준 연도를 뽑고, 없으면 올해로 둔다")
	void resolvesDefaultYear() {
		assertThat(CauNoticeCollector.defaultYear("2026.08.12")).isEqualTo(2026);
		assertThat(CauNoticeCollector.defaultYear("2025-01-03")).isEqualTo(2025);
		assertThat(CauNoticeCollector.defaultYear("")).isEqualTo(LocalDate.now().getYear());
		assertThat(CauNoticeCollector.defaultYear(null)).isEqualTo(LocalDate.now().getYear());
	}

	// --- 분류 ---

	@Test
	@DisplayName("근로장학은 분류값과 무관하게 WORK_STUDY 로 나눈다")
	void classifiesWorkStudy() {
		var classification = CauNoticeCollector.classify(
				"(통합) 2026학년도 2학기 2차 국가근로장학금 신청 안내", "통합");

		assertThat(classification.type()).isEqualTo(ScholarshipType.WORK_STUDY);
		assertThat(classification.provider()).isEqualTo("중앙대학교");
	}

	@Test
	@DisplayName("분류가 '외부'면 EXTERNAL 이며 제목에서 운영기관명을 뽑는다")
	void classifiesExternalWithProvider() {
		var classification = CauNoticeCollector.classify(
				"서암윤세영재단 2026년도 윤세영 스칼라십 신규 장학생 선발 안내", "외부");

		assertThat(classification.type()).isEqualTo(ScholarshipType.EXTERNAL);
		assertThat(classification.provider()).isEqualTo("서암윤세영재단");
	}

	@Test
	@DisplayName("캠퍼스 구분(서울·다빈치·통합)은 교내 장학이므로 INTERNAL 이다")
	void classifiesCampusCategoriesAsInternal() {
		assertThat(CauNoticeCollector.classify("2026-1학기 복지장학금 시행 공고(서울캠퍼스)", "서울").type())
				.isEqualTo(ScholarshipType.INTERNAL);
		assertThat(CauNoticeCollector.classify("2026-1학기 복지장학금 시행 공고(다빈치캠퍼스)", "다빈치").type())
				.isEqualTo(ScholarshipType.INTERNAL);
		assertThat(CauNoticeCollector.classify("2026학년도 2학기 국가장학금 신청 안내", "통합").type())
				.isEqualTo(ScholarshipType.INTERNAL);
	}

	// --- 상세 주소 ---

	@Test
	@DisplayName("정제 데이터에는 AJAX 주소가 아니라 사용자가 여는 상세 주소를 남긴다")
	void buildsUserFacingDetailUrl() {
		String url = CauNoticeCollector.detailUrl("30066");

		assertThat(url).startsWith("https://www.cau.ac.kr/cms/FR_CON/BoardView.do");
		assertThat(url).contains("BBS_SEQ=30066");
		assertThat(url).contains("BOARD_CATEGORY_NO=11");
		assertThat(url).doesNotContain("/ajax/");
	}
}
