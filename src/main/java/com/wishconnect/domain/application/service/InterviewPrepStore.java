package com.wishconnect.domain.application.service;

import com.wishconnect.domain.application.dto.response.InterviewPrepResponse;
import com.wishconnect.domain.application.entity.InterviewPrepQuestion;
import com.wishconnect.domain.application.repository.InterviewPrepQuestionRepository;
import com.wishconnect.domain.application.service.prompt.InterviewPrepPromptBuilder;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import com.wishconnect.domain.application.entity.Essay;
import com.wishconnect.domain.application.entity.EssayAnswer;
import com.wishconnect.domain.application.entity.InterviewPrepSampleAnswer;
import com.wishconnect.domain.application.repository.EssayAnswerRepository;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.application.repository.InterviewPrepSampleAnswerRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 면접 예상 질문의 DB 작업만 담당한다.
 *
 * <p><b>{@link InterviewPrepService} 와 굳이 나눈 이유가 있다.</b> 트랜잭션은 스프링 프록시로
 * 걸리므로 <b>같은 빈 안에서 부르면 {@code @Transactional} 이 무시된다.</b> 조회·저장을 짧은
 * 트랜잭션으로 감싸고 그 사이의 LLM 호출을 트랜잭션 밖에 두려면, DB 작업이 다른 빈에 있어야 한다.
 *
 * <p>이렇게 나눠야 LLM 이 느리거나 타임아웃 나는 동안 DB 커넥션을 붙잡지 않는다. 한 메서드를
 * 통째로 {@code @Transactional} 로 감싸면 동시 요청이 늘 때 커넥션 풀이 마르고 무관한 API 까지 느려진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewPrepStore {

	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final InterviewPrepQuestionRepository interviewPrepQuestionRepository;
	private final InterviewPrepSampleAnswerRepository interviewPrepSampleAnswerRepository;
	private final EssayRepository essayRepository;
	private final EssayAnswerRepository essayAnswerRepository;

	/** 저장된 질문을 읽는다. 없으면 빈 목록이 담긴 응답이다. */
	@Transactional(readOnly = true)
	public InterviewPrepResponse find(Long scholarshipId) {
		Scholarship scholarship = findScholarship(scholarshipId);
		return toResponse(scholarship,
				interviewPrepQuestionRepository.findWithGuideByScholarshipId(scholarshipId));
	}

	/**
	 * 생성에 필요한 재료를 모으고 사전 조건을 확인한다. LLM 호출 전에 끝나는 짧은 트랜잭션이다.
	 *
	 * @return {@code existing} 이 채워져 있으면 이미 질문이 있어 생성이 필요 없다는 뜻
	 */
	@Transactional(readOnly = true)
	public Prepared prepare(Long scholarshipId) {
		Scholarship scholarship = findScholarship(scholarshipId);
		requireInterview(scholarship);
		requireOpen(scholarship);

		List<InterviewPrepQuestion> existing = interviewPrepQuestionRepository
				.findByScholarship_IdOrderByDisplayOrderAsc(scholarshipId);
		if (!existing.isEmpty()) {
			return new Prepared(scholarship, List.of(), toResponse(scholarship, existing));
		}
		return new Prepared(scholarship,
				scholarshipConditionRepository.findAllByScholarshipId(scholarshipId), null);
	}

	/**
	 * 생성된 질문을 저장한다.
	 *
	 * <p>잠금이 만료됐거나 다른 인스턴스가 먼저 저장했으면 {@code (scholarship_id, display_order)}
	 * 유니크 제약에 걸린다. 그 경우 <b>실패가 아니라 이미 만들어진 질문을 돌려준다</b> —
	 * 호출자가 원한 결과가 이미 있는 것이므로 500 이 될 이유가 없다.
	 */
	@Transactional
	public InterviewPrepResponse save(Long scholarshipId,
			List<InterviewPrepPromptBuilder.GeneratedQuestion> generated) {
		Scholarship scholarship = findScholarship(scholarshipId);
		List<InterviewPrepQuestion> created = new ArrayList<>();
		for (int order = 0; order < generated.size(); order++) {
			InterviewPrepPromptBuilder.GeneratedQuestion item = generated.get(order);
			InterviewPrepQuestion question = InterviewPrepQuestion.builder()
					.scholarship(scholarship)
					.displayOrder(order)
					.questionText(item.questionText())
					.intent(item.intent())
					.answerTip(item.answerTip())
					.sampleAnswer(item.sampleAnswer())
					.build();
			for (InterviewPrepPromptBuilder.GuideStep step : item.guideSteps()) {
				question.addGuideStep(step.title(), step.description());
			}
			created.add(question);
		}
		try {
			interviewPrepQuestionRepository.saveAll(created);
			interviewPrepQuestionRepository.flush();
			return toResponse(scholarship, created);
		} catch (DataIntegrityViolationException e) {
			log.info("면접 예상 질문이 이미 저장돼 있어 기존 것을 사용합니다. scholarshipId={}", scholarshipId);
			throw new AlreadySavedException();
		}
	}


	/**
	 * 지원서 기준으로 질문 + 예시답변을 함께 읽는다.
	 *
	 * <p>예시답변은 지원서 단위라 여기서 합친다. 아직 만들지 않았으면 질문만 나가고
	 * 화면은 예시답변 자리를 비워 두면 된다.
	 */
	@Transactional(readOnly = true)
	public InterviewPrepResponse findForEssay(UUID userId, Long applicationId) {
		Essay essay = essayRepository.findByIdAndUser_Id(applicationId, userId)
				.orElseThrow(() -> new CustomException(ErrorCode.APPLICATION_NOT_FOUND));
		Long scholarshipId = essay.getScholarship().getId();
		Scholarship scholarship = findScholarship(scholarshipId);

		return InterviewPrepResponse.of(
				interviewPrepQuestionRepository.findWithGuideByScholarshipId(scholarshipId),
				sampleAnswersOf(applicationId),
				scholarship.getInterviewRequirement(), scholarship.getInterviewEvidence());
	}

	/** 예시답변 생성에 필요한 재료를 모은다. LLM 호출 전에 끝나는 짧은 트랜잭션이다. */
	@Transactional(readOnly = true)
	public SampleAnswerSource prepareSampleAnswers(UUID userId, Long applicationId) {
		Essay essay = essayRepository.findByIdAndUser_Id(applicationId, userId)
				.orElseThrow(() -> new CustomException(ErrorCode.APPLICATION_NOT_FOUND));
		Long scholarshipId = essay.getScholarship().getId();

		List<InterviewPrepQuestion> questions =
				interviewPrepQuestionRepository.findWithGuideByScholarshipId(scholarshipId);

		/*
		자소서 본문은 사용자가 확정한 글을 우선한다. 아직 확정 전이면 임시저장 내용을 쓰고,
		그것도 없으면 AI 초안을 쓴다. 셋 다 비어 있으면 재료가 없다는 뜻이라 호출하지 않는다.
		 */
		StringBuilder essayText = new StringBuilder();
		for (EssayAnswer answer : essayAnswerRepository.findByEssayQuestion_Essay_Id(applicationId)) {
			String content = answer.getUserContent() != null && !answer.getUserContent().isBlank()
					? answer.getUserContent()
					: answer.getAiDraft();
			if (content != null && !content.isBlank()) {
				essayText.append(answer.getEssayQuestion().getQuestionTitle()).append('\n')
						.append(content).append("\n\n");
			}
		}
		return new SampleAnswerSource(questions, essayText.toString().trim(),
				essay.getScholarship().getTitle());
	}

	/** 예시답변을 저장한다. 다시 만들면 이전 것을 지우고 새로 넣는다. */
	@Transactional
	public InterviewPrepResponse saveSampleAnswers(UUID userId, Long applicationId,
			Map<Long, String> answers) {
		Essay essay = essayRepository.findByIdAndUser_Id(applicationId, userId)
				.orElseThrow(() -> new CustomException(ErrorCode.APPLICATION_NOT_FOUND));

		interviewPrepSampleAnswerRepository.deleteByEssay_Id(applicationId);
		interviewPrepSampleAnswerRepository.flush();

		for (Map.Entry<Long, String> entry : answers.entrySet()) {
			InterviewPrepQuestion question = interviewPrepQuestionRepository.findById(entry.getKey())
					.orElse(null);
			if (question == null) {
				continue;
			}
			interviewPrepSampleAnswerRepository.save(InterviewPrepSampleAnswer.builder()
					.essay(essay)
					.question(question)
					.content(entry.getValue())
					.build());
		}
		return findForEssay(userId, applicationId);
	}

	private Map<Long, String> sampleAnswersOf(Long applicationId) {
		Map<Long, String> result = new LinkedHashMap<>();
		for (InterviewPrepSampleAnswer answer
				: interviewPrepSampleAnswerRepository.findByEssay_Id(applicationId)) {
			result.put(answer.getQuestion().getId(), answer.getContent());
		}
		return result;
	}

	/** {@link #prepareSampleAnswers} 결과. */
	public record SampleAnswerSource(List<InterviewPrepQuestion> questions, String essayText,
			String scholarshipTitle) {
	}

	/** 관리자 재생성용. 기존 질문을 지운다. */
	@Transactional
	public void clear(Long scholarshipId) {
		interviewPrepQuestionRepository.deleteByScholarship_Id(scholarshipId);
	}

	// --- 검증 ---

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

	/** 마감된 공고에는 준비할 면접이 없다. 크레딧을 쓸 이유가 없어 생성 대상에서 뺀다. */
	private void requireOpen(Scholarship scholarship) {
		if (scholarship.getRecruitmentStatus() == RecruitmentStatus.CLOSED) {
			throw new CustomException(ErrorCode.INTERVIEW_PREP_CLOSED_SCHOLARSHIP);
		}
	}

	private InterviewPrepResponse toResponse(Scholarship scholarship,
			List<InterviewPrepQuestion> questions) {
		return InterviewPrepResponse.of(questions,
				scholarship.getInterviewRequirement(), scholarship.getInterviewEvidence());
	}

	/**
	 * {@link #prepare} 결과. {@code existing} 이 있으면 생성이 필요 없다.
	 *
	 * <p>엔티티를 트랜잭션 밖으로 넘기지만 프롬프트가 쓰는 것은 제목·기관·요약·설명·근거처럼
	 * 전부 기본 컬럼이라 지연 로딩이 일어나지 않는다.
	 */
	public record Prepared(Scholarship scholarship, List<ScholarshipCondition> conditions,
			InterviewPrepResponse existing) {
	}

	/**
	 * 저장하려 했으나 다른 요청이 이미 만들어 둔 경우.
	 *
	 * <p>트랜잭션 안에서 잡아 그대로 조회하면 이미 롤백 표시가 붙은 트랜잭션이라 읽을 수 없다.
	 * 밖으로 던져 호출자가 새 트랜잭션으로 다시 읽게 한다.
	 */
	public static class AlreadySavedException extends RuntimeException {
	}
}
