package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 추천 순서.
 *
 * <p>고정하려는 성질은 둘이다. <b>정보가 부족한 공고가 위로 올라오지 않는다</b>,
 * 그리고 <b>한 기관이 화면을 덮지 않는다.</b>
 */
class ScholarshipRankerTest {

	private Scholarship bare() {
		return Scholarship.builder().title("공고").build();
	}

	private Scholarship filled() {
		return Scholarship.builder()
				.title("공고")
				.applicationEndAt(LocalDateTime.now().plusDays(20))
				.build();
	}

	@Test
	@DisplayName("조건도 마감일도 없는 공고는 신뢰도 점수를 못 받는다")
	void emptyNoticeScoresNoTrust() {
		var score = ScholarshipRanker.score(bare(), List.of(), 0, 0, Set.of(), null);

		assertThat(score.trust()).isZero();
		assertThat(score.total()).isZero();
	}

	@Test
	@DisplayName("자격이 다 맞아도, 정보가 없는 공고는 채워진 공고를 못 이긴다")
	void filledNoticeOutranksEmptyOne() {
		// 자격 게이트는 조건을 보고 거른다. 조건이 하나도 없으면 아무도 안 걸러져 전원 통과하고,
		// 그대로 두면 정보가 부족한 공고일수록 위로 올라온다.
		var empty = ScholarshipRanker.score(bare(), List.of(), 0, 0, Set.of(), null);
		var full = ScholarshipRanker.score(filled(), List.of(), 2, 2, Set.of(), 20L);

		assertThat(full.total()).isGreaterThan(empty.total());
	}

	@Test
	@DisplayName("마감이 가까울수록 점수가 커진다")
	void closerDeadlineScoresHigher() {
		var tomorrow = ScholarshipRanker.score(filled(), List.of(), 0, 0, Set.of(), 1L);
		var week = ScholarshipRanker.score(filled(), List.of(), 0, 0, Set.of(), 7L);
		var far = ScholarshipRanker.score(filled(), List.of(), 0, 0, Set.of(), 30L);

		assertThat(tomorrow.deadline()).isGreaterThan(week.deadline());
		assertThat(far.deadline()).isZero();
	}

	@Test
	@DisplayName("같은 기관이 세 번 연달아 오지 않는다")
	void spreadsSameProvider() {
		// 인천대는 학과마다 근로장학을 따로 올려서, 점수순으로만 두면 상위가 전부 한 학교가 된다.
		List<String> ranked = List.of("인천대", "인천대", "인천대", "인천대", "연세대", "고려대");

		List<String> spread = ScholarshipRanker.diversify(ranked, provider -> provider);

		assertThat(spread).hasSize(6).containsAll(ranked);
		for (int i = 0; i + 2 < spread.size(); i++) {
			assertThat(List.of(spread.get(i), spread.get(i + 1), spread.get(i + 2)))
					.as("%d번째부터 같은 기관 셋이 연달아 온다", i)
					.doesNotContainSequence("인천대", "인천대", "인천대");
		}
	}

	@Test
	@DisplayName("흩을 것이 없으면 순서를 그대로 둔다")
	void keepsOrderWhenNoRun() {
		List<String> ranked = List.of("연세대", "고려대", "인천대");

		assertThat(ScholarshipRanker.diversify(ranked, provider -> provider)).isEqualTo(ranked);
	}

	@Test
	@DisplayName("점수가 낮은 이유도 사용자에게 말해 준다")
	void explainsLowTrust() {
		var score = ScholarshipRanker.score(bare(), List.of(), 0, 0, Set.of(), null);

		assertThat(score.reasons()).contains("공고에 정보가 적어 직접 확인이 필요합니다");
	}
}
