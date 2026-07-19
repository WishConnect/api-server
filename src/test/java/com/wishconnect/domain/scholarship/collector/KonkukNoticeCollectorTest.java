package com.wishconnect.domain.scholarship.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KonkukNoticeCollectorTest {

	@Test
	@DisplayName("본문 'YYYY. M. D. ~ M. D.' 기간을 추출한다 (뒤 연도 생략 시 앞 연도 승계)")
	void parsesPeriodWithLeadingYear() {
		var period = KonkukNoticeCollector.parsePeriod(
				"신청기간: 2026. 7. 28. ~ 8. 19.(수요일 17시까지)", 2026);

		assertThat(period).isNotNull();
		assertThat(period.start()).isEqualTo(LocalDateTime.of(2026, 7, 28, 0, 0));
		assertThat(period.end().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 19));
	}

	@Test
	@DisplayName("제목 괄호형 '(7. 28. ~ 8. 19.)'은 기본 연도로 추출한다")
	void parsesPeriodWithoutYear() {
		var period = KonkukNoticeCollector.parsePeriod(
				"[교외][등록금] 장학생 선발 안내(7. 28. ~ 8. 19.)", 2026);

		assertThat(period).isNotNull();
		assertThat(period.start().getYear()).isEqualTo(2026);
		assertThat(period.start().getMonthValue()).isEqualTo(7);
		assertThat(period.end().getMonthValue()).isEqualTo(8);
	}

	@Test
	@DisplayName("연말~연초 범위(12월~1월)는 종료 연도를 +1 처리한다")
	void handlesYearRollover() {
		var period = KonkukNoticeCollector.parsePeriod("모집: 2026. 12. 20. ~ 1. 10.", 2026);

		assertThat(period).isNotNull();
		assertThat(period.end().getYear()).isEqualTo(2027);
	}

	@Test
	@DisplayName("기간 표기가 없으면 null")
	void returnsNullWhenNoPeriod() {
		assertThat(KonkukNoticeCollector.parsePeriod("장학생 선발 안내", 2026)).isNull();
	}
}
