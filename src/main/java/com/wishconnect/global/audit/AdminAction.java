package com.wishconnect.global.audit;

/**
 * 감사 로그에 남기는 관리자 행위.
 *
 * <p>읽기는 남기지 않는다. 되돌릴 수 없는 <b>쓰기</b>만 기록해 "누가 무엇을 바꿨는지" 를 추적한다.
 */
public enum AdminAction {

	/** 엑셀 일괄 반영(dryRun 아님). 한 번에 수백 행이 바뀌므로 가장 중요하다. */
	EXCEL_IMPORT,

	SCHOLARSHIP_CREATE,
	SCHOLARSHIP_UPDATE,
	/** 목록에서 내리기(soft delete). */
	SCHOLARSHIP_DELETE,

	REPORT_RESOLVE,

	/** 외부 API 호출·크롤링·LLM 과금을 유발하는 수동 트리거. */
	SYNC_TRIGGER,
	COLLECT_TRIGGER,
	CONDITION_EXTRACT_TRIGGER
}
