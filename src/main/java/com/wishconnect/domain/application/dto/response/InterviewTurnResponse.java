package com.wishconnect.domain.application.dto.response;

/**
 * 사전 인터뷰의 한 턴. answerText 는 사용자가 답변 전이면 null.
 */
public record InterviewTurnResponse(
		int stepOrder,
		String questionText,
		String answerText
) {
}
