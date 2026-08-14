package com.wishconnect.domain.scholarship.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 경희대 수집기의 파싱 규칙 검증.
 *
 * <p>제목·본문 예시는 실제 경희대 장학공지에서 가져온 표현을 줄인 것이다. 경희대는 공고 본문에
 * 근무기간이 거의 항상 들어 있어 신청기간과 혼동되기 쉬우므로, 실측에서 나온 오탐 케이스를
 * 회귀 테스트로 고정한다.
 */
class KhuNoticeCollectorTest {

	private static final String NO_BODY = "";

	// --- 제목에서 추출 ---

	@Test
	@DisplayName("제목에 범위가 통째로 있으면 시작일까지 살린다")
	void parsesRangeFromTitle() {
		// boardId=322745. 마감을 먼저 보면 시작일을 버리게 되므로 범위를 우선한다.
		String title = "2026-2학기 국가장학금 2차 신청 안내(2026.8.12.(화)~9.9.(수))";

		var period = KhuNoticeCollector.parsePeriod(title, NO_BODY, 2026);

		assertThat(period).isNotNull();
		assertThat(period.start().toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 12));
		assertThat(period.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 9));
	}

	@Test
	@DisplayName("제목의 '(~8/21까지)' 관례 표기를 마감일로 잡는다")
	void parsesSlashDeadlineFromTitle() {
		// boardId=322765. 경희대는 제목에 마감을 붙이는 관례가 뚜렷해 신뢰도가 가장 높다.
		String title = "[서울C] 2026학년도 2학기 LEE&MOON장학 신청 안내(~8/21까지)";

		var period = KhuNoticeCollector.parsePeriod(title, NO_BODY, 2026);

		assertThat(period).isNotNull();
		assertThat(period.start()).isNull();
		assertThat(period.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 21));
	}

	@Test
	@DisplayName("괄호 없이 붙은 요일 표기('9.9수까지')도 잡는다")
	void parsesBareWeekdaySuffix() {
		// boardId=322762. 요일을 괄호로 감싸지 않고 한 글자만 붙이는 공고가 있다.
		String title = "(~9.9수까지)2026-2학기 주거안정장학 2차 신청 공고";

		var period = KhuNoticeCollector.parsePeriod(title, NO_BODY, 2026);

		assertThat(period).isNotNull();
		assertThat(period.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 9));
	}

	@Test
	@DisplayName("'까지' 없이 물결로만 끝나는 마감 표기도 잡는다")
	void parsesDeadlineWithoutClosingWord() {
		// boardId=322748. 물결이 앞에 있으면 종결어가 없어도 마감으로 읽는다.
		String title = "2026년 하반기 군산시 대학생 학자금 이자 지원사업 안내(~9.4.(금))";

		var period = KhuNoticeCollector.parsePeriod(title, NO_BODY, 2026);

		assertThat(period).isNotNull();
		assertThat(period.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 4));
	}

	@Test
	@DisplayName("물결 없이 종결어도 없는 날짜는 마감으로 보지 않는다")
	void doesNotTreatBareDateAsDeadline() {
		// 물결·종결어가 모두 없으면 아무 날짜나 마감이 되므로 인정하지 않는다.
		assertThat(KhuNoticeCollector.parsePeriod("2026학년도 대한민국 인재상 선발 안내 2026.09.04", NO_BODY, 2026))
				.isNull();
	}

	// --- 본문에서 추출 ---

	@Test
	@DisplayName("본문의 '지원일정' 라벨 뒤를 신청기간으로 잡는다")
	void parsesFromApplicationScheduleLabel() {
		// boardId=322573. 경희대 근로/인턴장학 공고는 신청기간을 '지원일정' 으로 적는다.
		String body = "2. 모집인원 : 1명 3. 근무기간 : 2026.08.10 ~ 2027.02.28 4. 근무부서 : 평화의전당"
				+ " 9. 지원일정 : 2026.07.20(월) ~ 2026.07.31(금)까지";

		var period = KhuNoticeCollector.parsePeriod("경희인턴 모집 안내", body, 2026);

		assertThat(period).isNotNull();
		assertThat(period.start().toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 20));
		assertThat(period.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 31));
	}

	@Test
	@DisplayName("근무기간만 있고 신청기간 라벨이 없으면 기간 없음으로 둔다")
	void ignoresWorkPeriodWithoutLabel() {
		// boardId=322775. 근무기간을 신청기간으로 잡으면 마감 처리되어 공고가 통째로 버려진다.
		String body = "2. 근로기간 - 학기단위로 진행(졸업시까지 연속 선발 가능) - 2026.09.01 ~ 2027.02.28 (6개월)"
				+ " 3. 근로형태 - 시급단가: 10,320원";

		assertThat(KhuNoticeCollector.parsePeriod("경희인턴장학 모집 공고", body, 2026)).isNull();
	}

	@Test
	@DisplayName("'모집인원' 은 신청기간 라벨로 쓰지 않는다")
	void doesNotUseRecruitCountAsLabel() {
		// '모집' 을 약한 라벨로 쓰면 '모집인원' 에 걸려 바로 뒤 근무기간을 끌어온다.
		String body = "2. 모집인원 : 1명 3. 근무기간 : 2026.09.01 ~ 2027.02.28";

		assertThat(KhuNoticeCollector.parsePeriod("경희인턴 모집", body, 2026)).isNull();
	}

	@Test
	@DisplayName("표가 뭉개져 300일을 넘는 범위가 나오면 버린다")
	void discardsImplausiblyLongRange() {
		// boardId=322308. 근무일 안내 표가 텍스트로 뭉개져 무관한 두 날짜가 짝지어졌다.
		String body = "접수기간 매주 월요일 2026.07.13. 1명 ~2026.06.30. 매주 화요일 2026.07.14. 1명";

		var period = KhuNoticeCollector.parsePeriod("인사처 경희인턴장학 지원자 모집", body, 2026);

		// 범위는 버리고, 같은 라벨 범위 안의 마감 표기로 넘어간다.
		assertThat(period).isNotNull();
		assertThat(period.start()).isNull();
		assertThat(period.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 6, 30));
	}

	@Test
	@DisplayName("마감 시각이 적혀 있으면 그 시각까지로 본다")
	void keepsDeadlineTime() {
		var period = KhuNoticeCollector.parsePeriod("장학 신청 안내(~8/21 17:00까지)", NO_BODY, 2026);

		assertThat(period).isNotNull();
		assertThat(period.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 21));
		assertThat(period.end().toLocalTime().getHour()).isEqualTo(17);
	}

	@Test
	@DisplayName("연도 없는 해 넘김 표기는 종료일을 다음 해로 보정한다")
	void rollsOverYear() {
		var period = KhuNoticeCollector.parsePeriod("신청 안내(12. 20. ~ 1. 10.)", NO_BODY, 2026);

		assertThat(period).isNotNull();
		assertThat(period.start().toLocalDate()).isEqualTo(LocalDate.of(2026, 12, 20));
		assertThat(period.end().toLocalDate()).isEqualTo(LocalDate.of(2027, 1, 10));
	}

	// --- 분류 ---

	@Test
	@DisplayName("경희인턴은 표기가 갈려도 모두 WORK_STUDY 로 묶는다")
	void classifiesKhuInternAsWorkStudy() {
		// 같은 프로그램인데 '(교내인턴장학)' / '(교내장학)' 로 표기가 갈린다.
		assertThat(KhuNoticeCollector.classify("2026학년도 2학기 경희인턴(교내인턴장학) 모집 안내").type())
				.isEqualTo(ScholarshipType.WORK_STUDY);
		assertThat(KhuNoticeCollector.classify("2026학년도 2학기 경희인턴(교내장학) 장학생 모집 공고").type())
				.isEqualTo(ScholarshipType.WORK_STUDY);
		assertThat(KhuNoticeCollector.classify("26-2학기 학기중 국가근로장학 모집 안내").type())
				.isEqualTo(ScholarshipType.WORK_STUDY);
	}

	@Test
	@DisplayName("제목에 외부 재단명이 있으면 EXTERNAL 이며 기관명을 뽑는다")
	void classifiesExternalWithProvider() {
		var classification = KhuNoticeCollector.classify("2026학년도 2학기 동산장학회 장학생 모집 안내");

		assertThat(classification.type()).isEqualTo(ScholarshipType.EXTERNAL);
		assertThat(classification.provider()).isEqualTo("동산장학회");
	}

	@Test
	@DisplayName("그 외 교내 장학은 INTERNAL 이다")
	void classifiesInternal() {
		var classification = KhuNoticeCollector.classify("[서울C] 2026학년도 2학기 운연장학 신청 안내");

		assertThat(classification.type()).isEqualTo(ScholarshipType.INTERNAL);
		assertThat(classification.provider()).isEqualTo("경희대학교");
	}

	// --- 제목 정리 ---

	@Test
	@DisplayName("목록에서 딸려온 캠퍼스 라벨을 떼어낸다")
	void stripsCampusLabel() {
		assertThat(KhuNoticeCollector.cleanTitle("서울 [서울C] 2026학년도 2학기 운연장학 신청 안내"))
				.isEqualTo("[서울C] 2026학년도 2학기 운연장학 신청 안내");
		assertThat(KhuNoticeCollector.cleanTitle("국제 26-2학기 국가근로장학 모집 안내"))
				.isEqualTo("26-2학기 국가근로장학 모집 안내");
		assertThat(KhuNoticeCollector.cleanTitle("공통  2026년 하반기  군산시 안내"))
				.isEqualTo("2026년 하반기 군산시 안내");
	}

	// --- 상세 주소 ---

	@Test
	@DisplayName("상세 주소는 menuNo 와 boardId 를 함께 담는다")
	void buildsDetailUrl() {
		String url = KhuNoticeCollector.detailUrl("200318", "322765");

		assertThat(url).isEqualTo(
				"https://www.khu.ac.kr/kor/user/bbs/BMSR00040/view.do?menuNo=200318&boardId=322765");
	}
}
