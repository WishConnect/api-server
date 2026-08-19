package com.wishconnect.domain.application.dto.response;

import com.wishconnect.domain.application.entity.InterviewPrepQuestion;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 면접 예상 질문 응답.
 *
 * <p>사전 인터뷰({@link InterviewAdvanceResponse})와 다른 것이다. 이쪽은 <b>면접관이 물어볼 법한
 * 질문</b>이고 사용자 답변을 받지 않는다. 화면에서 두 가지를 같은 이름으로 부르면 사용자가
 * "내가 답한 질문"과 "면접에서 나올 질문"을 헷갈린다.
 *
 * @param questions           면접 예상 질문 목록 (displayOrder 오름차순)
 * @param interviewRequirement 면접 필요 여부. {@code null} 이면 공고에 언급이 없어 판단하지 못했다는 뜻
 * @param interviewEvidence   위 판단의 근거가 된 공고 문장. 우리 판단 대신 원문을 보여주기 위해 내려준다
 */
@Schema(description = "면접 예상 질문 응답. 면접관이 물어볼 법한 질문이며 사용자 답변을 받지 않는다.")
public record InterviewPrepResponse(
		@Schema(description = "면접 예상 질문 목록 (displayOrder 오름차순).")
		List<Item> questions,

		@Schema(description = "질문 개수.", example = "6")
		int totalCount,

		@Schema(description = "면접 필요 여부. null 은 공고에 언급이 없어 판단 불가라는 뜻이며, "
				+ "NOT_REQUIRED(면접 없음을 확인)와 다르게 표시해야 한다.",
				example = "CONDITIONAL")
		RequirementLevel interviewRequirement,

		@Schema(description = "면접 여부 판단의 근거가 된 공고 문장. 없으면 null.",
				example = "2차 면접전형은 서류 합격자에 한해 진행합니다.")
		String interviewEvidence
) {

	@Schema(description = "면접 예상 질문 한 건.")
	public record Item(
			@Schema(description = "노출 순서 (0부터).", example = "0")
			int displayOrder,

			@Schema(description = "질문 본문.",
					example = "이 장학금에 지원하게 된 이유는 무엇인가요?")
			String questionText,

			@Schema(description = "면접관이 이 질문으로 보려는 것. 준비 방향 안내용. 없으면 null.",
					example = "장학금 취지를 이해하고 지원했는지 확인하려는 질문입니다.")
			String intent
	) {
	}

	public static InterviewPrepResponse of(List<InterviewPrepQuestion> questions,
			RequirementLevel requirement, String evidence) {
		List<Item> items = questions.stream()
				.map(q -> new Item(q.getDisplayOrder(), q.getQuestionText(), q.getIntent()))
				.toList();
		return new InterviewPrepResponse(items, items.size(), requirement, evidence);
	}
}
