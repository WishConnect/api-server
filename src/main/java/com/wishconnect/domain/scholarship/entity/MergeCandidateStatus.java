package com.wishconnect.domain.scholarship.entity;

/**
 * 중복 장학금 병합 후보의 처리 상태.
 *
 * <p>LLM 이 만든 후보를 곧바로 병합하지 않고 사람 승인을 거치게 하기 위한 큐다.
 * 오판으로 멀쩡한 장학금을 지우면 사용자의 스크랩·자소서가 딸려 옮겨지므로,
 * 되돌리기 어려운 작업 앞에 관문을 하나 둔다.
 */
public enum MergeCandidateStatus {

	/** LLM 이 중복으로 판단해 올려둔 상태. 아직 아무 변경도 하지 않았다. */
	PENDING,

	/** 사람이 중복이 아니라고 판정. 다시 후보로 올리지 않는다. */
	REJECTED,

	/** 사람이 승인해 병합까지 완료. 중복 쪽은 소프트 삭제됐다. */
	MERGED,

	/** 승인했지만 병합 중 실패. 사유는 {@code note} 에 남는다. */
	FAILED
}
