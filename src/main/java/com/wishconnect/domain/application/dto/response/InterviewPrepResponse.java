package com.wishconnect.domain.application.dto.response;

import com.wishconnect.domain.application.entity.InterviewPrepGuideStep;
import com.wishconnect.domain.application.entity.InterviewPrepQuestion;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * 면접 예상 질문 응답.
 *
 * <p>사전 인터뷰({@link InterviewAdvanceResponse})와 다른 것이다. 이쪽은 <b>면접관이 물어볼 법한
 * 질문</b>이고 사용자 답변을 받지 않는다. 화면에서 두 가지를 같은 이름으로 부르면 사용자가
 * "내가 답한 질문"과 "면접에서 나올 질문"을 헷갈린다.
 *
 * <p>질문 하나에 네 가지가 함께 나간다 — 질문의도 · 답변 Tip · 예시답변 · 구성 가이드.
 * 이 중 <b>예시답변만 사용자가 쓴 자기소개서 기반</b>이라 없을 수 있다.
 *
 * @param interviewRequirement 면접 필요 여부. {@code null} 이면 공고에 언급이 없어 판단하지 못했다는 뜻
 * @param interviewEvidence    위 판단의 근거가 된 공고 문장. 우리 판단 대신 원문을 보여주기 위해 내려준다
 */
@Schema(description = "면접 예상 질문 응답. 질문마다 의도·Tip·예시답변·구성가이드가 함께 온다.")
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

	@Schema(description = "면접 예상 질문 한 건과 답변 준비 자료.")
	public record Item(
			@Schema(description = "질문 ID.", example = "12")
			Long questionId,

			@Schema(description = "노출 순서 (0부터).", example = "0")
			int displayOrder,

			@Schema(description = "질문 본문.", example = "본인의 가장 큰 장점은 무엇인가요?")
			String questionText,

			@Schema(description = "질문의도 — 면접관이 이 질문으로 무엇을 보려는지. 없으면 null.",
					example = "지원자의 핵심 역량과 강점을 파악하기 위한 질문입니다.")
			String intent,

			@Schema(description = "답변 Tip — 답변할 때 유의할 점. 없으면 null.",
					example = "구체적인 경험을 들어 설명하면 신뢰도를 높일 수 있어요.")
			String answerTip,

			@Schema(description = "예시답변. 자소서를 쓴 경우 그 내용을 바탕으로 개인화되고, "
					+ "자소서를 받지 않는 장학금은 공고 기반 일반 예시가 온다. 둘 다 없으면 null.",
					example = "저의 가장 큰 강점은 '지속적으로 배우고 성장하려는 능력'입니다...")
			String sampleAnswer,

			@Schema(description = "예시답변이 사용자의 자기소개서를 바탕으로 만들어졌는지. "
					+ "false 면 공고 기반 일반 예시다. 화면에서 '내 자소서 기반' 배지를 붙일지 정하는 데 쓴다.",
					example = "true")
			boolean sampleAnswerPersonalized,

			@Schema(description = "구성 가이드 — 답변 흐름 3단계. 만들지 못했으면 빈 배열.")
			List<GuideStep> guideSteps
	) {
	}

	@Schema(description = "답변 구성 가이드 한 단계.")
	public record GuideStep(
			@Schema(description = "단계 순서 (0부터). 화면은 STEP1 부터로 표시한다.", example = "0")
			int stepOrder,

			@Schema(description = "단계 이름.", example = "강점제시")
			String title,

			@Schema(description = "이 단계에서 무엇을 말할지.",
					example = "본인의 핵심 강점을 한 문장으로 먼저 명확히 제시하세요.")
			String description
	) {
	}

	/**
	 * 예시답변 없이 응답을 만든다. 장학금 단위 조회(자소서와 무관한 화면)에서 쓴다.
	 */
	public static InterviewPrepResponse of(List<InterviewPrepQuestion> questions,
			RequirementLevel requirement, String evidence) {
		return of(questions, Map.of(), requirement, evidence);
	}

	/**
	 * 예시답변을 얹어 응답을 만든다.
	 *
	 * @param sampleAnswers 질문 ID → <b>자소서 기반 개인화</b> 예시답변.
	 *                      없는 질문은 공고 기반 일반 예시로 대체된다
	 */
	public static InterviewPrepResponse of(List<InterviewPrepQuestion> questions,
			Map<Long, String> sampleAnswers, RequirementLevel requirement, String evidence) {
		List<Item> items = questions.stream()
				.map(q -> new Item(
						q.getId(),
						q.getDisplayOrder(),
						q.getQuestionText(),
						q.getIntent(),
						q.getAnswerTip(),
						// 개인화 답변이 있으면 그것을, 없으면 공고 기반 일반 예시를 쓴다.
						sampleAnswers.getOrDefault(q.getId(), q.getSampleAnswer()),
						sampleAnswers.containsKey(q.getId()),
						q.getGuideSteps().stream()
								.map(InterviewPrepResponse::toGuideStep)
								.toList()))
				.toList();
		return new InterviewPrepResponse(items, items.size(), requirement, evidence);
	}

	private static GuideStep toGuideStep(InterviewPrepGuideStep step) {
		return new GuideStep(step.getStepOrder(), step.getTitle(), step.getDescription());
	}
}
