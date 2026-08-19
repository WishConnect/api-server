package com.wishconnect.domain.application.entity;

/**
 * 지원서 문항이 어디서 온 것인지.
 *
 * <p>생성 API 가 성공했는지 남겨 두기 위한 값이다. 기록하지 않으면 같은 지원서로 다시 호출할 때
 * 이미 만든 문항을 지우고 새로 만들게 되어, 프론트 재시도·더블클릭·화면 재진입만으로 LLM 비용과
 * 사용자 생성 한도가 또 소모되고 {@code questionId} 가 바뀌어 화면이 들고 있던 ID 가 무효가 된다.
 */
public enum EssayQuestionSource {

	/** 지원서 생성 시 넣은 고정 문항. 맞춤 생성을 아직 하지 않았거나, 근거가 부족해 유지된 상태. */
	DEFAULT,

	/** 공고를 근거로 만든 맞춤 문항. 이 상태면 다시 생성하지 않는다. */
	GENERATED
}
