package com.wishconnect.domain.scholarship.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 공지 신원.
 *
 * <p>운영에서 서로 다른 공지 9건이 한 장학금 행에 묶였다. 제목을 재료로 썼는데 게시판 스킨이
 * 셀렉터에 안 걸려 그 학교의 모든 공지가 같은 제목을 갖게 된 탓이다. 여기서 고정하려는 것은
 * <b>재료가 뭉개져도 신원은 안 뭉개진다</b>는 성질이다.
 */
class ScholarshipDedupKeyTest {

	@Test
	@DisplayName("게시글 번호가 다르면 키가 다르다 — 제목·기간이 어떻든 상관없다")
	void differentArticlesNeverCollide() {
		String a = ScholarshipDedupKey.of("UNIV_HALLYM", "387838");
		String b = ScholarshipDedupKey.of("UNIV_HALLYM", "387835");

		assertThat(a).isNotEqualTo(b);
	}

	@Test
	@DisplayName("같은 공지를 다시 수집하면 같은 키다 — 새 행을 만들지 않는다")
	void sameArticleKeepsItsRow() {
		assertThat(ScholarshipDedupKey.of("UNIV_HALLYM", "387838"))
				.isEqualTo(ScholarshipDedupKey.of("UNIV_HALLYM", "387838"));
		// 앞뒤 공백은 같은 번호로 본다(파싱 과정에서 섞여 들어온다).
		assertThat(ScholarshipDedupKey.of("UNIV_HALLYM", " 387838 "))
				.isEqualTo(ScholarshipDedupKey.of("UNIV_HALLYM", "387838"));
	}

	@Test
	@DisplayName("학교가 다르면 게시글 번호가 같아도 다른 공지다")
	void sameArticleNumberAcrossSchoolsIsDifferent() {
		assertThat(ScholarshipDedupKey.of("UNIV_HALLYM", "1001"))
				.isNotEqualTo(ScholarshipDedupKey.of("UNIV_KOREA", "1001"));
	}

	@Test
	@DisplayName("공지 번호가 없으면 키를 만들지 않는다 — 신원 없는 행이 생기면 또 뭉친다")
	void refusesToBuildWithoutAnIdentifier() {
		assertThatThrownBy(() -> ScholarshipDedupKey.of("UNIV_HALLYM", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ScholarshipDedupKey.of("UNIV_HALLYM", "  "))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("dedup_key 컬럼 길이(64)에 맞는다")
	void fitsTheColumn() {
		assertThat(ScholarshipDedupKey.of("UNIV_HALLYM", "387838")).hasSize(64);
	}
}
