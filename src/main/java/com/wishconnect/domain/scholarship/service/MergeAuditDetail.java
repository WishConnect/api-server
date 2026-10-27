package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.MergeResultResponse;

/**
 * 병합·반려 감사 기록의 요약 문구. 단건 API 와 일괄 API 가 같은 형식으로 남기도록 한곳에 둔다.
 *
 * <p>콘솔 작업 기록 화면이 이 문구에서 장학금 ID 와 옮긴 참조 건수를 읽는다. 형식을 바꾸면
 * admin-console.js 의 병합 기록 해석도 함께 고칠 것.
 */
public final class MergeAuditDetail {

	private MergeAuditDetail() {
	}

	/** 예: "중복 307 를 683 로 병합. {scrap.moved=2, ...}" */
	public static String merged(MergeResultResponse result) {
		return "중복 %d 를 %d 로 병합. %s".formatted(result.duplicateId(), result.primaryId(), result.moved());
	}

	/** 예: "중복 후보 12 반려 (683 vs 307). 캠퍼스가 다름" */
	public static String rejected(Long candidateId, MergeResultResponse result, String note) {
		return "중복 후보 %d 반려 (%d vs %d). %s".formatted(
				candidateId, result.primaryId(), result.duplicateId(), note == null ? "" : note);
	}

	/** 일괄 처리 표시. 예: "일괄 병합 12건 중 3번째 · " */
	public static String bulkPrefix(String verb, int total, int position) {
		return "일괄 %s %d건 중 %d번째 · ".formatted(verb, total, position);
	}
}
