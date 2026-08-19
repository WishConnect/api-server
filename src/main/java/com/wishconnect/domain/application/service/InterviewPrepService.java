package com.wishconnect.domain.application.service;

import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.dto.response.InterviewPrepResponse;
import com.wishconnect.domain.application.service.prompt.InterviewPrepPromptBuilder;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.Duration;
import com.wishconnect.domain.application.service.prompt.InterviewSampleAnswerPromptBuilder;
import java.util.Map;
import com.wishconnect.global.lock.RedisLock;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 면접 예상 질문 제공.
 *
 * <p>사전 인터뷰({@link InterviewService})와 다른 기능이다. 저쪽은 자기소개서를 쓸 재료를 모으려고
 * AI 가 사용자에게 묻고 답변을 저장하며, 이쪽은 면접관이 물어볼 법한 질문을 예측해 읽을거리로 준다.
 *
 * <p><b>장학금 단위로 한 번 만들어 캐시한다.</b> 같은 장학금을 준비하는 사용자끼리 질문이 같아도
 * 무방하므로 사용자 수만큼 LLM 을 부를 이유가 없다. 그리고 자소서는 필요 없는데 면접만 보는
 * 장학금이 있어(essay NOT_REQUIRED + interview REQUIRED) 지원서에 매달 수도 없다.
 *
 * <h2>LLM 호출은 트랜잭션 밖에서 한다</h2>
 * DB 작업은 전부 {@link InterviewPrepStore} 가 맡고, 이 클래스는 조회 → LLM 호출 → 저장 순서만
 * 잡는다. 한 메서드를 통째로 {@code @Transactional} 로 감싸면 LLM 이 느린 동안 DB 커넥션을
 * 붙잡아, 동시 요청이 늘 때 커넥션 풀이 마르고 무관한 API 까지 느려진다.
 *
 * <h2>비용을 세 겹으로 막는다</h2>
 * 로그인만 하면 부를 수 있는 API 라, 장학금 ID 를 순회하며 호출하면 크레딧이 그대로 나간다.
 * <ol>
 *   <li>사용자별 신규 생성 제한 — {@value #USER_QUOTA} 건 / {@value #QUOTA_WINDOW_HOURS} 시간</li>
 *   <li>장학금별 생성 잠금 — 동시 요청이 같은 장학금에 LLM 을 두 번 부르지 않게 한다</li>
 *   <li>마감된 장학금 제외 — 준비할 면접이 없다 ({@link InterviewPrepStore} 에서 검사)</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPrepService {

	/** 한 사용자가 {@value #QUOTA_WINDOW_HOURS} 시간 동안 새로 생성할 수 있는 장학금 수. */
	private static final int USER_QUOTA = 10;
	private static final int QUOTA_WINDOW_HOURS = 24;
	private static final String QUOTA_KEY = "interview-prep:quota:";

	/** 장학금별 생성 잠금. LLM 호출 시간을 덮되, 실패해도 오래 잠기지 않게 짧게 둔다. */
	private static final String LOCK_KEY = "interview-prep:lock:";
	/** 예시답변은 지원서 단위라 잠금도 지원서 단위로 잡는다. */
	private static final String SAMPLE_LOCK_KEY = "interview-prep:sample-lock:";
	private static final Duration LOCK_TTL = Duration.ofSeconds(90);

	/** 다른 요청이 생성 중일 때 기다리는 간격·횟수. 합쳐서 최대 5초. */
	private static final long WAIT_INTERVAL_MILLIS = 500L;
	private static final int WAIT_ATTEMPTS = 10;

	private final InterviewPrepStore store;
	private final InterviewPrepPromptBuilder promptBuilder;
	private final InterviewSampleAnswerPromptBuilder sampleAnswerPromptBuilder;
	private final LlmClient llmClient;
	private final RedisLock redisLock;
	private final StringRedisTemplate redisTemplate;

	/**
	 * 저장된 면접 예상 질문을 조회한다. <b>없어도 생성하지 않는다.</b>
	 *
	 * <p>조회에 LLM 을 태우면 화면을 열기만 해도 크레딧이 나간다. 생성은
	 * {@link #generate(UUID, Long)} 으로 분리해 프론트가 의도적으로 부르게 한다.
	 */
	public InterviewPrepResponse get(Long scholarshipId) {
		return store.find(scholarshipId);
	}

	/**
	 * 면접 예상 질문을 생성한다. 이미 있으면 재생성하지 않고 그대로 돌려준다.
	 *
	 * <p>동시에 같은 장학금으로 두 요청이 들어와도 LLM 은 한 번만 부르고, 뒤늦은 요청은 먼저
	 * 만들어진 질문을 받는다. 잠금을 못 잡은 경우와 저장이 유니크 제약에 걸린 경우 모두
	 * 다시 읽어 돌려주므로 <b>실패가 아니라 같은 결과</b>가 된다.
	 *
	 * @param userId 호출한 사용자. 비용 제한을 사용자별로 걸기 위해 받는다
	 */
	public InterviewPrepResponse generate(UUID userId, Long scholarshipId) {
		InterviewPrepStore.Prepared prepared = store.prepare(scholarshipId);
		if (prepared.existing() != null) {
			return prepared.existing();
		}

		// 잠금을 못 잡았다면 다른 요청이 지금 만들고 있다. LLM 을 또 부르지 않고 기다렸다 읽는다.
		Optional<String> lockToken = redisLock.tryLock(LOCK_KEY + scholarshipId, LOCK_TTL);
		if (lockToken.isEmpty()) {
			return awaitExisting(scholarshipId);
		}
		try {
			// 잠금을 잡기까지 사이에 앞선 요청이 저장을 마쳤을 수 있다.
			InterviewPrepResponse afterLock = store.find(scholarshipId);
			if (afterLock.totalCount() > 0) {
				return afterLock;
			}
			consumeQuota(userId);

			// --- 트랜잭션 밖. 이 구간에서 DB 커넥션을 잡지 않는다 ---
			List<InterviewPrepPromptBuilder.GeneratedQuestion> generated = promptBuilder.parse(
					llmClient.chat(promptBuilder.build(prepared.scholarship(), prepared.conditions())));

			if (generated.isEmpty()) {
				log.warn("면접 예상 질문 생성 결과가 비어 있습니다. scholarshipId={}", scholarshipId);
				throw new CustomException(ErrorCode.INTERVIEW_PREP_GENERATION_FAILED);
			}
			if (generated.size() < InterviewPrepPromptBuilder.QUESTION_COUNT) {
				// 개수가 모자라도 준비 자료로는 쓸모가 있으므로 막지 않고 기록만 남긴다.
				log.warn("면접 예상 질문이 요청 개수보다 적게 생성됐습니다. scholarshipId={}, 생성={}, 요청={}",
						scholarshipId, generated.size(), InterviewPrepPromptBuilder.QUESTION_COUNT);
			}
			try {
				return store.save(scholarshipId, generated);
			} catch (InterviewPrepStore.AlreadySavedException e) {
				// 다른 인스턴스가 먼저 저장했다. 원한 결과가 이미 있으므로 그것을 돌려준다.
				return store.find(scholarshipId);
			}
		} finally {
			// 내가 잡은 잠금만 해제한다. TTL 이 만료돼 다른 요청이 새로 잡았다면 건드리지 않는다.
			redisLock.unlock(LOCK_KEY + scholarshipId, lockToken.get());
		}
	}


	/**
	 * 지원서 기준 면접 준비 자료 조회. 질문 + 예시답변을 함께 돌려준다. <b>LLM 을 부르지 않는다.</b>
	 */
	public InterviewPrepResponse getForEssay(UUID userId, Long applicationId) {
		return store.findForEssay(userId, applicationId);
	}

	/**
	 * 사용자가 쓴 자기소개서를 바탕으로 예시답변을 만든다.
	 *
	 * <p>질문은 장학금 단위로 이미 만들어져 있어야 한다. 없으면 만들 대상이 없으므로 그대로 돌려준다.
	 *
	 * <p><b>실패해도 예외를 던지지 않는다.</b> 예시답변은 있으면 좋은 보조 자료이고, 없다고
	 * 질문·의도·Tip·구성가이드까지 못 보게 만들 이유가 없다. 자소서가 비어 있거나 LLM 이
	 * 실패하면 질문만 돌려준다.
	 */
	public InterviewPrepResponse generateSampleAnswers(UUID userId, Long applicationId) {
		InterviewPrepStore.SampleAnswerSource source = store.prepareSampleAnswers(userId, applicationId);

		if (source.questions().isEmpty()) {
			log.info("면접 질문이 아직 없어 예시답변을 만들지 않습니다. applicationId={}", applicationId);
			return store.findForEssay(userId, applicationId);
		}
		if (source.essayText().length() < InterviewSampleAnswerPromptBuilder.MIN_ESSAY_CHARS) {
			// 자소서가 비면 지어낼 수밖에 없다. 빈 답이 없는 경험이 적힌 답보다 낫다.
			log.info("자기소개서 내용이 부족해 예시답변을 만들지 않습니다. applicationId={}", applicationId);
			return store.findForEssay(userId, applicationId);
		}

		Optional<String> lockToken = redisLock.tryLock(SAMPLE_LOCK_KEY + applicationId, LOCK_TTL);
		if (lockToken.isEmpty()) {
			return store.findForEssay(userId, applicationId);
		}
		try {
			consumeQuota(userId);

			// --- 트랜잭션 밖. 이 구간에서 DB 커넥션을 잡지 않는다 ---
			Map<Long, String> answers;
			try {
				answers = sampleAnswerPromptBuilder.parse(
						llmClient.chat(sampleAnswerPromptBuilder.build(
								source.questions(), source.essayText(), source.scholarshipTitle())),
						source.questions());
			} catch (Exception e) {
				log.warn("예시답변 생성이 실패해 질문만 돌려줍니다. applicationId={} : {}",
						applicationId, e.getMessage());
				return store.findForEssay(userId, applicationId);
			}
			if (answers.isEmpty()) {
				log.info("쓸 만한 예시답변이 없어 질문만 돌려줍니다. applicationId={}", applicationId);
				return store.findForEssay(userId, applicationId);
			}
			return store.saveSampleAnswers(userId, applicationId, answers);
		} finally {
			redisLock.unlock(SAMPLE_LOCK_KEY + applicationId, lockToken.get());
		}
	}

	/** 관리자용 재생성 준비. 기존 질문을 지운다. 다음 생성 요청이 새로 만든다. */
	public void clear(Long scholarshipId) {
		store.clear(scholarshipId);
	}

	// --- 동시성 ---


	/**
	 * 다른 요청이 만드는 동안 잠깐 기다렸다 읽는다.
	 *
	 * <p>끝까지 못 기다렸으면 빈 목록을 돌려준다. 오류로 만들지 않는 이유는, 잠시 뒤 조회하면
	 * 질문이 있을 것이고 화면은 이미 "비어 있으면 생성" 흐름을 갖고 있기 때문이다.
	 */
	private InterviewPrepResponse awaitExisting(Long scholarshipId) {
		for (int attempt = 0; attempt < WAIT_ATTEMPTS; attempt++) {
			try {
				Thread.sleep(WAIT_INTERVAL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			InterviewPrepResponse response = store.find(scholarshipId);
			if (response.totalCount() > 0) {
				return response;
			}
		}
		log.info("다른 요청이 생성 중이라 빈 결과를 돌려줍니다. scholarshipId={}", scholarshipId);
		return store.find(scholarshipId);
	}

	// --- 비용 ---

	/** 사용자별 신규 생성 횟수를 제한한다. 이미 만들어진 질문 조회는 여기 걸리지 않는다. */
	private void consumeQuota(UUID userId) {
		String key = QUOTA_KEY + userId;
		Long used = redisTemplate.opsForValue().increment(key);
		if (used == null) {
			return;
		}
		if (used == 1L) {
			redisTemplate.expire(key, Duration.ofHours(QUOTA_WINDOW_HOURS));
		}
		if (used > USER_QUOTA) {
			log.warn("면접 예상 질문 생성 한도 초과. userId={}, used={}", userId, used);
			throw new CustomException(ErrorCode.INTERVIEW_PREP_QUOTA_EXCEEDED);
		}
	}
}
