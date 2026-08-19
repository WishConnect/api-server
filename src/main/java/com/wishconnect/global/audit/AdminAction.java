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
	/** 조건·서류·심사 분기를 포함한 관리자 통합 수정. */
	SCHOLARSHIP_AGGREGATE_UPDATE,
	SCHOLARSHIP_IMAGE_UPDATE,
	MERGE_CANDIDATE_MANUAL_CREATE,
	/** 목록에서 내리기(soft delete). */
	SCHOLARSHIP_DELETE,

	REPORT_RESOLVE,
	CONTENT_INQUIRY_RESOLVE,

	/** 외부 API 호출·크롤링·LLM 과금을 유발하는 수동 트리거. */
	SYNC_TRIGGER,
	COLLECT_TRIGGER,
	CONDITION_EXTRACT_TRIGGER,

	/** 공공데이터 조건에 마스터 참조 채우기. 과금은 없지만 추천 결과를 바꾸는 쓰기다. */
	CONDITION_REF_BACKFILL,

	/** 상세페이지·첨부·포스터 자동 보완. 외부 검색·크롤링을 유발한다. */
	ENRICH_TRIGGER,

	/** 중복 장학금 후보 탐지 배치 실행. */
	MERGE_DETECT_TRIGGER,

	/** 중복 장학금 병합 승인. 사용자 데이터(스크랩·자소서)가 옮겨지는 파괴적 작업이라 반드시 남긴다. */
	SCHOLARSHIP_MERGE,

	/** 중복 후보 반려("중복 아님" 판정). */
	SCHOLARSHIP_MERGE_REJECT
}
