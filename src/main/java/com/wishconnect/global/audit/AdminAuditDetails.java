package com.wishconnect.global.audit;

/** 여러 화면이 같은 모양으로 남기는 작업 기록 요약 문구. */
public final class AdminAuditDetails {

	private AdminAuditDetails() {
	}

	/**
	 * 신고·문의 처리 요약. 상태 다음에 답변(사용자·기관에게 보이는 문구)을 남긴다.
	 * 상태가 앞에 와야 예전 기록("RESOLVED")과 같은 모양으로 읽힌다. 예: "RESOLVED · 답변: 마감일을 고쳤습니다."
	 */
	public static String resolution(String status, String adminNote) {
		return adminNote == null || adminNote.isBlank() ? status : status + " · 답변: " + adminNote.trim();
	}
}
