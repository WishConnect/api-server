package com.wishconnect.domain.application.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * ④ STEP1 사전 인터뷰 응답.
 *
 * <p>인터뷰 시작·답변 저장 어느 경우든 <b>해당 문항의 사전 질문 전체</b>를 현재 답변 상태와 함께
 * 돌려준다. 클라이언트는 이 응답만으로 화면을 다시 그릴 수 있다.
 *
 * @param questions           문항의 사전 질문 전체. stepOrder 오름차순. 답변 전이면 answerText 는 null
 * @param totalCount          질문 개수
 * @param answeredCount       답변이 채워진 질문 개수
 * @param isInterviewComplete 모든 질문에 답변이 채워졌는지 여부
 * @param canGenerateDraft    STEP2 초안 생성(⑤ DRAFT)을 호출할 수 있는지 여부. 1건 이상 답하면 true
 */
@Schema(description = "STEP1 사전 인터뷰 응답. 질문 전체와 현재 답변 상태를 함께 반환한다.")
public record InterviewAdvanceResponse(
		@Schema(description = "문항의 사전 질문 전체 (stepOrder 오름차순).")
		List<InterviewTurnResponse> questions,

		@Schema(description = "질문 개수.", example = "5")
		int totalCount,

		@Schema(description = "답변이 채워진 질문 개수.", example = "3")
		int answeredCount,

		@Schema(description = "모든 질문에 답변이 채워졌는지 여부.", example = "false")
		boolean isInterviewComplete,

		@Schema(description = "STEP2 초안 생성을 호출할 수 있는지 여부 (1건 이상 답변 시 true).",
				example = "true")
		boolean canGenerateDraft
) {

	/**
	 * 질문 목록으로부터 진행 상태 필드를 계산해 응답을 만든다.
	 */
	public static InterviewAdvanceResponse of(List<InterviewTurnResponse> questions) {
		int total = questions.size();
		int answered = (int) questions.stream()
				.filter(q -> q.answerText() != null && !q.answerText().isBlank())
				.count();
		return new InterviewAdvanceResponse(
				questions,
				total,
				answered,
				total > 0 && answered == total,
				answered > 0);
	}
}
