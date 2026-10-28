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
	/** 목록에서 내리기(soft delete). detail 에 사유가 남는다. */
	SCHOLARSHIP_DELETE,
	/** 내린 장학금 복원. */
	SCHOLARSHIP_RESTORE,

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
	SCHOLARSHIP_MERGE_REJECT,

	/** 반려한 중복 후보를 다시 승인 대기로 올림(반려 취소). */
	MERGE_CANDIDATE_REOPEN,

	/**
	 * 감사 로그 복구 자체. before/after 에 복구 직전·직후 스냅샷이 남아, 이 기록으로 복구를 다시 되돌릴 수 있다.
	 * detail 에 원래 기록 ID 와 되돌린 필드가 남는다.
	 */
	AUDIT_RESTORE,

	/**
	 * 실패 재처리 원본 만료 처리(목록에서 숨기기). 요청 1회당 1건. detail 에 건수·사유·연결 장학금 마감 건수가 남는다.
	 * 연결 장학금을 마감했으면 장학금마다 SCHOLARSHIP_UPDATE(전후 스냅샷)가 따로 남는다.
	 */
	RAW_SCHOLARSHIP_DISMISS,

	/** 원본 만료 처리 되돌리기. 장학금 상태는 되돌리지 않는다. */
	RAW_SCHOLARSHIP_RESTORE,

	/** 관리자 로그인 성공. detail 에 IP. */
	ADMIN_LOGIN_SUCCESS,
	/**
	 * 관리자 로그인 실패(없는 아이디·비밀번호 오류·ADMIN 아님 — 응답은 모두 같다). 계정을 알면 actor 를, 모르면 null.
	 * detail 에 가린 아이디·IP·실패 횟수. 비밀번호는 남기지 않는다.
	 */
	ADMIN_LOGIN_FAILURE,
	/** 실패가 쌓여 잠겼거나, 잠긴 상태에서 다시 시도함. */
	ADMIN_LOGIN_LOCKED,
	/** 관리자 로그아웃(토큰으로 누군지 알 수 있을 때만). */
	ADMIN_LOGOUT,

	/** 대학 공지 LLM 파싱·재처리 수동 실행. LLM 비용이 든다. detail 에 조건·처리 건수. */
	LLM_PARSE_TRIGGER,
	/** 공공데이터 조건 보강(LLM) 수동 실행. */
	KOSAF_CONDITION_TRIGGER,

	/**
	 * 관리자의 쓰기 요청이 400·500 으로 실패함(성공한 작업만 남던 빈틈). detail 에 메서드·경로·상태·오류 코드.
	 * 아무것도 바뀌지 않은 시도의 기록이다.
	 */
	ADMIN_REQUEST_FAILED,
	/** 일괄 병합·반려 중 실패한 후보(그 건은 바뀌지 않음). 200 응답 안의 실패라 ADMIN_REQUEST_FAILED 로는 잡히지 않는다. */
	MERGE_CANDIDATE_FAILED,

	/** 인사이트 수동 수집(외부 검색 API). */
	INSIGHT_SYNC_TRIGGER,
	/** 학교·전공 마스터 수동 동기화(추천 결과에 영향). */
	UNIVERSITY_SYNC_TRIGGER,

	/** 검수 대기(보류) 중인 새 장학금을 관리자가 공개함. 중복·이상 확인을 사람이 대신한 것이다. */
	SCHOLARSHIP_PUBLISH
}
