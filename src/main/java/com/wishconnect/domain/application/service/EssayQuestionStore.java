package com.wishconnect.domain.application.service;

import com.wishconnect.domain.application.dto.response.EssayQuestionGenerationResponse;
import com.wishconnect.domain.application.entity.Essay;
import com.wishconnect.domain.application.entity.EssayAnswer;
import com.wishconnect.domain.application.entity.EssayQuestion;
import com.wishconnect.domain.application.entity.EssayQuestionSource;
import com.wishconnect.domain.application.repository.AiInterviewRepository;
import com.wishconnect.domain.application.repository.EssayAnswerRepository;
import com.wishconnect.domain.application.repository.EssayQuestionRepository;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.application.service.prompt.EssayQuestionPromptBuilder;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자기소개서 문항의 DB 작업만 담당한다.
 *
 * <p>{@link EssayQuestionGenerationService} 와 나눈 이유는 트랜잭션 때문이다. 트랜잭션은 스프링
 * 프록시로 걸리므로 <b>같은 빈 안에서 부르면 {@code @Transactional} 이 무시된다.</b> 조회·교체를
 * 짧은 트랜잭션으로 감싸고 그 사이 LLM 호출을 트랜잭션 밖에 두려면 빈이 나뉘어야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EssayQuestionStore {

	private final EssayRepository essayRepository;
	private final EssayQuestionRepository essayQuestionRepository;
	private final EssayAnswerRepository essayAnswerRepository;
	private final AiInterviewRepository aiInterviewRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final ScholarshipRepository scholarshipRepository;

	/**
	 * 생성에 필요한 재료를 모으고 교체가 가능한 상태인지 확인한다.
	 *
	 * <p><b>이미 작성을 시작했으면 막는다.</b> 문항을 바꾸려면 그 문항에 딸린 답변과 사전 인터뷰를
	 * 지워야 하는데, 학생이 쓴 글을 없애는 것은 어떤 맞춤 문항보다 나쁘다.
	 */
	@Transactional(readOnly = true)
	public Prepared prepare(UUID userId, Long applicationId) {
		Essay essay = essayRepository.findByIdAndUser_Id(applicationId, userId)
				.orElseThrow(() -> new CustomException(ErrorCode.APPLICATION_NOT_FOUND));

		List<EssayQuestion> questions = essayQuestionRepository
				.findByEssay_IdOrderByQuestionOrderAsc(applicationId);

		/*
		이미 맞춤 문항으로 교체된 지원서면 다시 만들지 않는다. 재시도·더블클릭·화면 재진입으로
		같은 API 가 또 불려도 LLM 을 부르지 않고, 한도도 깎지 않고, questionId 도 그대로 둔다.
		이 검사가 없으면 성공한 요청을 다시 보내는 것만으로 비용이 나가고 화면이 들고 있던
		ID 가 무효가 된다.
		 */
		if (essay.getQuestionSource() == EssayQuestionSource.GENERATED) {
			return new Prepared(null, List.of(),
					response(EssayQuestionGenerationResponse.Source.GENERATED, questions, null));
		}
		if (hasStartedWriting(questions)) {
			throw new CustomException(ErrorCode.ESSAY_QUESTIONS_LOCKED);
		}

		/*
		essay.getScholarship() 은 지연 로딩 프록시다. 그대로 트랜잭션 밖으로 넘기면 프롬프트를
		조립할 때 LazyInitializationException 이 난다 — 필드가 기본 컬럼이어도 프록시 자체가
		초기화되지 않아 소용없다. 여기서 실제 엔티티로 다시 읽어 넘긴다.
		 */
		Long scholarshipId = essay.getScholarship().getId();
		Scholarship scholarship = scholarshipRepository.findById(scholarshipId)
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
		return new Prepared(scholarship,
				scholarshipConditionRepository.findAllByScholarshipId(scholarshipId), null);
	}

	/**
	 * 기본 문항을 맞춤 문항으로 교체한다.
	 *
	 * <p>기존 문항에 딸린 답변·사전 인터뷰를 먼저 지운다. 여기까지 온 것은 학생이 아직 아무것도
	 * 쓰지 않았다는 뜻이므로({@link #prepare} 에서 확인) 지워도 잃는 것이 없다. 다만 그 사이
	 * 작성이 시작됐을 수 있어 <b>교체 직전에 다시 확인한다.</b>
	 */
	@Transactional
	public EssayQuestionGenerationResponse replace(UUID userId, Long applicationId,
			List<EssayQuestionPromptBuilder.GeneratedQuestion> generated) {
		Essay essay = essayRepository.findByIdAndUser_Id(applicationId, userId)
				.orElseThrow(() -> new CustomException(ErrorCode.APPLICATION_NOT_FOUND));

		List<EssayQuestion> existing = essayQuestionRepository
				.findByEssay_IdOrderByQuestionOrderAsc(applicationId);
		if (hasStartedWriting(existing)) {
			// LLM 을 부르는 사이 학생이 쓰기 시작했다. 맞춤 문항을 버리고 쓰던 것을 지킨다.
			log.info("생성 중 작성이 시작돼 문항 교체를 취소합니다. applicationId={}", applicationId);
			return response(EssayQuestionGenerationResponse.Source.DEFAULT, existing,
					"작성이 시작돼 문항을 바꾸지 않았습니다.");
		}

		for (EssayQuestion question : existing) {
			aiInterviewRepository.deleteByEssayQuestion_Id(question.getId());
			essayAnswerRepository.deleteByEssayQuestion_Id(question.getId());
		}
		essayQuestionRepository.deleteAll(existing);
		essayQuestionRepository.flush();

		List<EssayQuestion> created = new ArrayList<>();
		int order = 1;
		for (EssayQuestionPromptBuilder.GeneratedQuestion item : generated) {
			created.add(EssayQuestion.builder()
					.essay(essay)
					.questionOrder(order++)
					.questionTitle(item.title())
					.questionDescription(item.description())
					.charLimit(item.charLimit())
					.build());
		}
		essayQuestionRepository.saveAll(created);

		// 답변 레코드는 문항과 1:1 로 미리 만들어 둔다. 지원서 생성과 같은 규칙이다.
		for (EssayQuestion question : created) {
			essayAnswerRepository.save(EssayAnswer.builder()
					.essayQuestion(question)
					.charCount(0)
					.isTemporary(true)
					.isCompleted(false)
					.build());
		}
		// 교체 성공을 남긴다. 다음 호출은 LLM 없이 현재 문항을 돌려준다.
		essay.markQuestionsGenerated();
		return response(EssayQuestionGenerationResponse.Source.GENERATED, created, null);
	}

	/** 교체 없이 현재 문항을 그대로 돌려준다. 근거가 부족해 기본 문항을 유지할 때 쓴다. */
	@Transactional(readOnly = true)
	public EssayQuestionGenerationResponse current(UUID userId, Long applicationId, String reason) {
		essayRepository.findByIdAndUser_Id(applicationId, userId)
				.orElseThrow(() -> new CustomException(ErrorCode.APPLICATION_NOT_FOUND));
		return response(EssayQuestionGenerationResponse.Source.DEFAULT,
				essayQuestionRepository.findByEssay_IdOrderByQuestionOrderAsc(applicationId), reason);
	}

	/**
	 * 학생이 이미 쓰기 시작했는지 판단한다.
	 *
	 * <p>초안 생성·임시저장·완료뿐 아니라 <b>사전 인터뷰 답변도 작성으로 본다.</b> 인터뷰 답변은
	 * 초안의 재료라 문항이 바뀌면 쓸모가 없어지는데, 학생 입장에서는 답한 내용이 사라지는 것이다.
	 */
	private boolean hasStartedWriting(List<EssayQuestion> questions) {
		for (EssayQuestion question : questions) {
			boolean answered = essayAnswerRepository.findByEssayQuestion_Id(question.getId())
					.map(answer -> answer.getCharCount() > 0
							|| answer.getAiDraft() != null
							|| answer.isCompleted())
					.orElse(false);
			if (answered) {
				return true;
			}
			boolean interviewed = aiInterviewRepository
					.findByEssayQuestion_IdOrderByStepOrderAsc(question.getId()).stream()
					.anyMatch(interview -> interview.getAnswerText() != null
							&& !interview.getAnswerText().isBlank());
			if (interviewed) {
				return true;
			}
		}
		return false;
	}

	private EssayQuestionGenerationResponse response(
			EssayQuestionGenerationResponse.Source source,
			List<EssayQuestion> questions, String reason) {
		return new EssayQuestionGenerationResponse(source,
				questions.stream()
						.map(q -> new EssayQuestionGenerationResponse.Item(
								q.getId(), q.getQuestionOrder(), q.getQuestionTitle(),
								q.getQuestionDescription(), q.getCharLimit()))
						.toList(),
				reason);
	}

	/**
	 * {@link #prepare} 결과. 프롬프트 조립에 쓸 재료다.
	 *
	 * <p>엔티티를 트랜잭션 밖으로 넘기므로 <b>프록시가 아닌 실제 엔티티여야 한다.</b>
	 * 프롬프트가 읽는 것은 제목·기관·요약·설명처럼 전부 기본 컬럼이다.
	 */
	public record Prepared(Scholarship scholarship, List<ScholarshipCondition> conditions,
			EssayQuestionGenerationResponse existing) {
	}
}
