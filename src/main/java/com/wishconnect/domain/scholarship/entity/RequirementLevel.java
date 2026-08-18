package com.wishconnect.domain.scholarship.entity;

/**
 * 자기소개서·면접이 필요한 정도.
 *
 * <p>{@code boolean} 으로 두면 <b>"명시적으로 없음" 과 "언급 없음" 이 똑같이 false</b> 가 된다.
 * 기존 {@code ScholarshipDocument.essay} 플래그가 그 문제를 갖고 있었다 — 서류 이름에 키워드가
 * 없으면 무조건 false 라, 자소서가 필요한 장학금이 필요 없다고 표시됐다.
 *
 * <p>값이 {@code null} 이면 <b>공고에 언급이 없어 모른다</b>는 뜻이다. {@link #NOT_REQUIRED}
 * ("확인했고 없다")와 구분해야 한다. 화면에서도 다르게 보여줘야 한다 — 전자는 "공고 확인 필요",
 * 후자는 "면접 없음" 이라고 적을 수 있다.
 */
public enum RequirementLevel {

	/** 필수. "자기소개서 제출", "면접전형 진행" */
	REQUIRED,

	/**
	 * 조건부. "서류 합격자에 한해", "1차 통과자만"
	 *
	 * <p>실무적으로 가장 흔하고 가장 중요하다. {@link #REQUIRED} 로 뭉뚱그리면 서류만 내면 되는 줄
	 * 알았던 학생이 놀라고, {@link #NOT_REQUIRED} 로 두면 준비를 아예 안 한다.
	 */
	CONDITIONAL,

	/** 명시적으로 없음. "면접 없이 서류로만 선발" */
	NOT_REQUIRED
}
