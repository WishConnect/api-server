package com.wishconnect.domain.application.service;

import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.dto.response.InterviewPrepResponse;
import com.wishconnect.domain.application.entity.InterviewPrepQuestion;
import com.wishconnect.domain.application.repository.InterviewPrepQuestionRepository;
import com.wishconnect.domain.application.service.prompt.InterviewPrepPromptBuilder;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 면접 예상 질문 제공.
 *
 * <p>사전 인터뷰({@link InterviewService})와 다른 기능이다. 저쪽은 자기소개서를 쓸 재료를 모으려고
 * AI 가 사용자에게 묻고 답변을 저장하며, 이쪽은 면접관이 물어볼 법한 질문을 예측해 읽을거리로 준다.
 *
 * <p><b>장학금 단위로 한 번 만들어 캐시한다.</b> 같은 장학금을 준비하는 사용자끼리 질문이 같아도
 * 무방하므로, 사용자 수만큼 LLM 을 부를 이유가 없다. 그리고 자소서는 필요 없는데 면접만 보는
 * 장학금이 있어(essay NOT_REQUIRED + interview REQUIRED) 지원서에 매달 수도 없다.
 *
 * <p>TODO: 트랜잭션 안에서 LLM 을 호출하므로 생성 요청은 커넥션 점유 시간이 길다.
 *   {@link InterviewService} 와 같은 문제이며, 함께 조회/호출/저장 3단계로 분리할 것.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPrepService {

	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final InterviewPrepQuestionRepository interviewPrepQuestionRepository;
	private final InterviewPrepPromptBuilder promptBuilder;
	private final LlmClient llmClient;

	/**
	 * 저장된 면접 예상 질문을 조회한다. <b>없어도 생성하지 않는다.</b>
	 *
	 * <p>조회에 LLM 을 태우면 화면을 열기만 해도 크레딧이 나간다. 생성은
	 * {@link #generate(Long)} 으로 분리해 프론트가 의도적으로 부르게 한다.
	 */
	@Transactional(readOnly = true)
	public InterviewPrepResponse get(Long scholarshipId) {
		Scholarship scholarship = findScholarship(scholarshipId);
		return InterviewPrepResponse.of(
				interviewPrepQuestionRepository.findByScholarship_IdOrderByDisplayOrderAsc(scholarshipId),
				scholarship.getInterviewRequirement(),
				scholarship.getInterviewEvidence());
	}

	/**
	 * 면접 예상 질문을 생성한다. 이미 있으면 재생성하지 않고 그대로 돌려준다.
	 *
	 * <p>여러 번 불러도 안전하다. 화면 진입마다 호출해도 LLM 은 첫 호출에만 탄다.
	 */
	@Transactional
	public InterviewPrepResponse generate(Long scholarshipId) {
		Scholarship scholarship = findScholarship(scholarshipId);
		requireInterview(scholarship);

		List<InterviewPrepQuestion> existing = interviewPrepQuestionRepository
				.findByScholarship_IdOrderByDisplayOrderAsc(scholarshipId);
		if (!existing.isEmpty()) {
			return InterviewPrepResponse.of(existing,
					scholarship.getInterviewRequirement(), scholarship.getInterviewEvidence());
		}

		List<InterviewPrepPromptBuilder.Generated> generated = promptBuilder.parse(
				llmClient.chat(promptBuilder.build(scholarship,
						scholarshipConditionRepository.findAllByScholarshipId(scholarshipId))));
		if (generated.isEmpty()) {
			log.warn("면접 예상 질문 생성 결과가 비어 있습니다. scholarshipId={}", scholarshipId);
			throw new CustomException(ErrorCode.INTERVIEW_PREP_GENERATION_FAILED);
		}
		if (generated.size() < InterviewPrepPromptBuilder.QUESTION_COUNT) {
			// 개수가 모자라도 준비 자료로는 쓸모가 있으므로 막지 않고 기록만 남긴다.
			log.warn("면접 예상 질문이 요청 개수보다 적게 생성됐습니다. scholarshipId={}, 생성={}, 요청={}",
					scholarshipId, generated.size(), InterviewPrepPromptBuilder.QUESTION_COUNT);
		}

		List<InterviewPrepQuestion> created = new ArrayList<>();
		for (int order = 0; order < generated.size(); order++) {
			InterviewPrepPromptBuilder.Generated item = generated.get(order);
			created.add(InterviewPrepQuestion.builder()
					.scholarship(scholarship)
					.displayOrder(order)
					.questionText(item.questionText())
					.intent(item.intent())
					.build());
		}
		interviewPrepQuestionRepository.saveAll(created);

		return InterviewPrepResponse.of(created,
				scholarship.getInterviewRequirement(), scholarship.getInterviewEvidence());
	}

	private Scholarship findScholarship(Long scholarshipId) {
		return scholarshipRepository.findById(scholarshipId)
				.filter(found -> found.getDeletedAt() == null)
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
	}

	/**
	 * 면접을 보지 않는다고 밝힌 장학금에는 질문을 만들지 않는다.
	 *
	 * <p><b>{@code null} 은 막지 않는다.</b> {@link RequirementLevel} 의 null 은 "공고에 언급이 없어
	 * 모른다"는 뜻이고 {@link RequirementLevel#NOT_REQUIRED}("확인했고 없다")와 다르다. 모르는 것을
	 * 없는 것으로 취급하면, 아직 파싱되지 않았거나 본문이 부실한 공고에서 면접 준비를 아예
	 * 못 하게 된다. 확실할 때만 막는다.
	 */
	private void requireInterview(Scholarship scholarship) {
		if (scholarship.getInterviewRequirement() == RequirementLevel.NOT_REQUIRED) {
			throw new CustomException(ErrorCode.INTERVIEW_NOT_REQUIRED);
		}
	}
}
