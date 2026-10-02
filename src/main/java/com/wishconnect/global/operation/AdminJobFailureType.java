package com.wishconnect.global.operation;

/**
 * 배치 실패 유형. 같은 "실패" 라도 조치가 다르므로 나눈다.
 *
 * <p>LLM 크레딧 부족·인증 오류는 <b>재처리해도 소용없고 사람이 결제·키를 고쳐야 하는</b> 실패라 따로 둔다.
 * 2026-09 크레딧이 며칠간 바닥났는데 배치가 SUCCEEDED 로 기록돼 아무도 몰랐던 일이 계기다.
 */
public enum AdminJobFailureType {
	LLM_CREDIT("LLM 크레딧 부족"),
	LLM_AUTH("LLM 인증 오류"),
	LLM_ERROR("LLM 호출 실패"),
	COLLECT("수집 실패"),
	PARSE("응답 해석 실패"),
	SAVE("저장 실패"),
	STEP_ERROR("단계 전체 실패"),
	OTHER("기타");

	private final String label;

	AdminJobFailureType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** 사람이 결제·키를 고치기 전에는 해소되지 않는 실패. 대시보드 경고에 쓴다. */
	public boolean needsOperatorAction() {
		return this == LLM_CREDIT || this == LLM_AUTH;
	}
}
