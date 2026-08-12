package com.wishconnect.domain.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * ④ STEP1 사전 인터뷰 요청 바디.
 *
 * <p>인터뷰 시작(첫 호출)에는 body 를 비우거나 {@code answers} 를 null 로 보낸다.
 * 서버가 문항별 사전 질문을 일괄 생성해 응답으로 돌려준다.
 *
 * <p>이후에는 답변을 {@code answers} 배열에 담아 보낸다. <b>부분 제출을 허용</b>하므로
 * 한 건씩 자동 저장해도 되고, 다 채운 뒤 5건을 한 번에 보내도 된다. 같은 stepOrder 로
 * 다시 보내면 기존 답변을 덮어쓴다(수정 지원).
 *
 * @param answers 저장할 답변 목록. 인터뷰 시작 시 null 또는 빈 배열.
 */
@Schema(description = "STEP1 사전 인터뷰 요청. 인터뷰 시작이면 body 를 비워서 호출한다.")
public record InterviewAnswerRequest(
		@Schema(description = "저장할 답변 목록. 부분 제출 가능. 인터뷰 시작 시 null.")
		List<InterviewAnswerItem> answers
) {

	/** null 을 빈 리스트로 정규화해 반환한다. */
	public List<InterviewAnswerItem> safeAnswers() {
		return answers == null ? List.of() : answers;
	}
}
