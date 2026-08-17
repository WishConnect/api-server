package com.wishconnect.domain.scholarship.dto;

/**
 * 비로그인 큐레이팅의 정렬 드롭다운. 피그마 `큐레이팅_로그인 전` 기준 두 가지다.
 *
 * <p>추천 점수가 없는 상태라 정렬 기준이 곧 목록의 순서가 된다.
 * 로그인 상태에서는 화면에 드롭다운이 없어 이 값을 쓰지 않는다.
 */
public enum CuratedSort {

	/** 마감 임박순. 마감일이 없는 공고는 뒤로 민다. */
	DEADLINE,

	/** 최신 등록순. */
	LATEST
}
