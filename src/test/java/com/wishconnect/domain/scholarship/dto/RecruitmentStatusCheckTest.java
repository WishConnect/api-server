package com.wishconnect.domain.scholarship.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("모집 상태·기간 모순 점검")
class RecruitmentStatusCheckTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 10, 0);

	@Test
	@DisplayName("마감일이 지났는데 OPEN 이면 모순이다 (QA #725: 8/27 마감인데 OPEN)")
	void openAfterDeadline() {
		RecruitmentStatusCheck check = RecruitmentStatusCheck.of(RecruitmentStatus.OPEN,
				LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 27, 23, 59), NOW);

		assertThat(check.deadlinePassed()).isTrue();
		assertThat(check.consistent()).isFalse();
		assertThat(check.warnings()).singleElement().asString().contains("마감일이 지났는데");
		assertThat(check.serverNow()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("마감 지난 CLOSED, 기간 안의 OPEN, 마감일 없는 ALWAYS_OPEN 은 정상이다")
	void consistentCases() {
		assertThat(RecruitmentStatusCheck.of(RecruitmentStatus.CLOSED, null, NOW.minusDays(1), NOW).consistent()).isTrue();
		assertThat(RecruitmentStatusCheck.of(RecruitmentStatus.OPEN, NOW.minusDays(1), NOW.plusDays(1), NOW).consistent())
				.isTrue();
		assertThat(RecruitmentStatusCheck.of(RecruitmentStatus.ALWAYS_OPEN, null, null, NOW).consistent()).isTrue();
	}

	@Test
	@DisplayName("마감 전 CLOSED·시작 전 OPEN·지난 시작일의 UPCOMING 도 알려 준다")
	void otherWarnings() {
		assertThat(RecruitmentStatusCheck.of(RecruitmentStatus.CLOSED, null, NOW.plusDays(3), NOW).warnings())
				.anyMatch(w -> w.contains("조기 마감"));
		assertThat(RecruitmentStatusCheck.of(RecruitmentStatus.OPEN, NOW.plusDays(1), NOW.plusDays(9), NOW).beforeStart())
				.isTrue();
		assertThat(RecruitmentStatusCheck.of(RecruitmentStatus.UPCOMING, NOW.minusDays(1), NOW.plusDays(9), NOW)
				.consistent()).isFalse();
	}
}
