package com.wishconnect.domain.application.entity;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 면접 예상 질문. 면접관이 물어볼 법한 질문을 미리 보여주는 준비 자료다.
 *
 * <p><b>{@link AiInterview} 와 혼동하기 쉬우니 구분해 둔다.</b>
 *
 * <pre>
 *                  AiInterview(사전 인터뷰)        InterviewPrepQuestion(면접 예상 질문)
 * 누가 묻나        AI 가 사용자에게                면접관이 지원자에게 (물어볼 법한 것을 예측)
 * 목적             자기소개서 쓸 재료 수집          면접 대비
 * 사용자 답변      받는다 (초안 생성의 입력)        받지 않는다 (읽기 전용)
 * 범위             지원서 문항별                   장학금별
 * </pre>
 *
 * <p>장학금 단위로 두는 이유는 두 가지다. 첫째, 자소서는 필요 없는데 면접만 보는 장학금이 있어
 * (essay NOT_REQUIRED + interview REQUIRED) 지원서에 매달면 그 조합에서 질문을 줄 수 없다.
 * 둘째, 같은 장학금을 준비하는 사용자끼리 질문이 같아도 무방하므로 한 번 만들어 공유하면
 * 사용자 수만큼 LLM 을 부르지 않아도 된다.
 */
@Entity
@Getter
@Table(name = "interview_prep_question")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class InterviewPrepQuestion extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "scholarship_id", nullable = false)
	private Scholarship scholarship;

	/** 노출 순서 (0부터). 장학금 안에서 유일하다(DB 유니크 제약). */
	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
	private String questionText;

	/**
	 * 이 질문이 왜 나올 만한지. 사용자가 준비 방향을 잡도록 질문과 함께 보여준다.
	 *
	 * <p>LLM 이 채우지 못하면 {@code null} 이다. 질문만으로도 쓸모가 있으므로 없다고 버리지 않는다.
	 */
	@Column(name = "intent", columnDefinition = "TEXT")
	private String intent;

	/**
	 * 답변할 때 유의할 점. "구체적인 경험을 들어 설명하면 신뢰도를 높일 수 있어요" 같은 한 문장.
	 *
	 * <p>LLM 이 채우지 못하면 {@code null} 이다. 질문·의도만으로도 쓸모가 있어 버리지 않는다.
	 */
	@Column(name = "answer_tip", columnDefinition = "TEXT")
	private String answerTip;

	/**
	 * 장학금 정보만으로 만든 일반 예시답변.
	 *
	 * <p>자기소개서를 받지 않는 장학금(essay NOT_REQUIRED + interview REQUIRED)에는 개인화할
	 * 재료가 없다. 예시답변을 통째로 비우면 그 장학금 준비생은 답변 예시를 아예 못 보므로,
	 * 공고 정보만으로 만든 일반 예시를 여기 둔다. 질문 생성과 같은 호출에서 만들어 비용이 없다.
	 *
	 * <p>자소서가 있으면 {@link InterviewPrepSampleAnswer}(지원서 단위)의 개인화 답변이 이 값을 덮는다.
	 */
	@Column(name = "sample_answer", columnDefinition = "TEXT")
	private String sampleAnswer;

	/**
	 * 답변 구성 가이드. STEP1 → STEP2 → STEP3 흐름으로 보여준다.
	 *
	 * <p>{@code cascade}·{@code orphanRemoval} 을 두어 질문을 지우면 함께 사라지게 한다.
	 * 관리자 재생성 시 가이드만 남아 다음 질문에 붙는 것을 막는다.
	 */
	@jakarta.persistence.OneToMany(mappedBy = "question",
			cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
	@jakarta.persistence.OrderBy("stepOrder asc")
	@Builder.Default
	private java.util.List<InterviewPrepGuideStep> guideSteps = new java.util.ArrayList<>();

	/** 생성 시점에 가이드 단계를 붙인다. 양방향 연관을 한곳에서 맞춘다. */
	public void addGuideStep(String title, String description) {
		this.guideSteps.add(InterviewPrepGuideStep.builder()
				.question(this)
				.stepOrder(this.guideSteps.size())
				.title(title)
				.description(description)
				.build());
	}
}
