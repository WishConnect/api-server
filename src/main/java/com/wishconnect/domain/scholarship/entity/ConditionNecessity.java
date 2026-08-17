package com.wishconnect.domain.scholarship.entity;

/**
 * 조건이 자격요건인지 우대사항인지.
 *
 * <p>이 구분이 없으면 조건을 성실히 뽑을수록 추천이 비어간다. 공고문에는 자격요건만큼
 * 우대사항이 많은데, {@code ConditionMatcher} 는 모든 조건을 하드 게이트로 취급하기 때문이다
 * ({@code eligible = mismatchCount == 0}). "우대: 봉사활동 실적자" 가 조건으로 들어가면
 * 봉사 실적이 없는 학생이 자격은 충분한데도 탈락한다.
 *
 * <p>기존 행에는 {@code REQUIRED} 를 기본값으로 넣는다. 지금 작동 중인 소득·성적·학년 판정을
 * 그대로 보존하기 위해서다 — NULL 로 두면 게이트가 통째로 풀려 "조건 미충족" 섹션이 비어버린다.
 */
public enum ConditionNecessity {

	/** 자격요건. 불충족이면 지원할 수 없다 — 게이트로 쓴다. */
	REQUIRED,

	/**
	 * 우대사항·가산점. 불충족이어도 지원할 수 있다 — 게이트가 아니라 점수로 쓴다.
	 *
	 * <p>{@code FINANCIAL_AID_TYPE}(생활비·등록금·해외연수 같은 지원 성격)은 애초에 자격이
	 * 아니라 분류라서 언제나 이쪽이다.
	 */
	PREFERRED
}
