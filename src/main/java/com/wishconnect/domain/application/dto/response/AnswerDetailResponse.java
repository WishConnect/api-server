package com.wishconnect.domain.application.dto.response;

/**
 * 문항별 답변 상세 (AI 초안 + 사용자 수정본).
 */
public record AnswerDetailResponse(
		String aiDraft,
		String userContent,
		int charCount,
		boolean isTemporary,
		boolean isCompleted
) {
}
