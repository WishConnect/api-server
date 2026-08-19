package com.wishconnect.domain.application.service;

import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.dto.response.EssayQuestionGenerationResponse;
import com.wishconnect.domain.application.service.prompt.EssayQuestionPromptBuilder;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.wishconnect.global.lock.RedisLock;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 장학금에 맞춘 자기소개서 문항 생성.
 *
 * <p>지금까지 문항은 "지원 동기", "성장 배경 및 자기소개" 두 개로 고정이었다. 어떤 장학금이든
 * 같은 것을 물으니 지역인재 장학금인지 이공계 연구 장학금인지가 글에 드러나지 않았다.
 * 이 서비스는 공고를 읽고 그 장학금에 맞는 카테고리와 질문을 만들어 교체한다.
 *
 * <h2>지원서 생성과 분리한 이유</h2>
 * 지원서를 만들 때 LLM 을 부르면 생성이 몇 초 걸리고, <b>LLM 이 실패하면 지원서 생성 자체가
 * 실패한다.</b> 그래서 지원서는 기본 문항으로 즉시 만들고, 맞춤 문항은 이 API 로 따로 만든다.
 * 실패해도 지원서는 남고 기본 문항으로 계속 쓸 수 있다.
 *
 * <h2>근거가 부족하면 기본 문항을 지킨다</h2>
 * 공고에 없는 것을 물으면 학생이 없는 경험을 지어내게 된다. 문항마다 공고에서 인용한 근거를
 * 받아 실제 공고에 있는지 대조하고, 살아남은 문항이 모자라면 <b>교체하지 않고 기본 문항을
 * 그대로 둔다.</b> 응답의 {@code source} 로 어느 쪽인지 알려준다.
 *
 * <p>LLM 호출은 트랜잭션 밖에서 한다. DB 작업은 {@link EssayQuestionStore} 가 맡는다 —
 * 한 메서드를 통째로 감싸면 LLM 이 느린 동안 커넥션을 붙잡아 무관한 API 까지 느려진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EssayQuestionGenerationService {

	/** 한 사용자가 {@value #QUOTA_WINDOW_HOURS} 시간 동안 문항을 생성할 수 있는 지원서 수. */
	private static final int USER_QUOTA = 20;
	private static final int QUOTA_WINDOW_HOURS = 24;
	private static final String QUOTA_KEY = "essay-question:quota:";

	/** 지원서별 생성 잠금. 같은 지원서에 두 요청이 겹쳐 문항이 두 번 교체되는 것을 막는다. */
	private static final String LOCK_KEY = "essay-question:lock:";
	private static final Duration LOCK_TTL = Duration.ofSeconds(90);

	private final EssayQuestionStore store;
	private final EssayQuestionPromptBuilder promptBuilder;
	private final LlmClient llmClient;
	private final RedisLock redisLock;
	private final StringRedisTemplate redisTemplate;

	/**
	 * 지원서의 문항을 장학금 맞춤 문항으로 교체한다.
	 *
	 * <p>근거가 부족하면 교체하지 않고 기본 문항을 유지한다. 어느 쪽이든 <b>200 으로 현재 문항을
	 * 돌려주므로</b> 화면은 응답만 보고 다시 그리면 된다.
	 *
	 * @throws CustomException 이미 작성을 시작했거나(409), 한도를 넘겼거나(429),
	 *                         지원서가 없을 때(404)
	 */
	public EssayQuestionGenerationResponse generate(UUID userId, Long applicationId) {
		EssayQuestionStore.Prepared prepared = store.prepare(userId, applicationId);
		if (prepared.existing() != null) {
			// 이미 맞춤 문항으로 교체된 지원서다. LLM 도 한도도 쓰지 않고 그대로 돌려준다.
			return prepared.existing();
		}

		Optional<String> lockToken = redisLock.tryLock(LOCK_KEY + applicationId, LOCK_TTL);
		if (lockToken.isEmpty()) {
			// 같은 지원서에 이미 생성이 돌고 있다. 두 번 교체하지 않는다.
			return store.current(userId, applicationId, "문항 생성이 이미 진행 중입니다.");
		}
		try {
			consumeQuota(userId);

			String sourceText = promptBuilder.sourceTextOf(
					prepared.scholarship(), prepared.conditions());

			/*
			LLM 호출만 따로 감싼다. 전체를 catch(Exception) 으로 덮으면 한도 초과·지원서 없음 같은
			신호까지 삼켜 버리고, CustomException 만 다시 던지면 LLM 실패도 CustomException 으로
			오기 때문에(LlmClient 가 그렇게 감싼다) 폴백이 건너뛰어진다. 실제로 그 상태에서
			LLM 서버를 내리자 500 이 나갔다.

			이 구간은 트랜잭션 밖이라 DB 커넥션을 잡지 않는다.
			 */
			String raw;
			try {
				raw = llmClient.chat(promptBuilder.build(
						prepared.scholarship(), prepared.conditions()));
			} catch (Exception e) {
				// LLM 장애가 지원서를 못 쓰게 만들면 안 된다. 기본 문항으로 계속 쓸 수 있어야 한다.
				log.warn("LLM 호출이 실패해 기본 문항을 유지합니다. applicationId={} : {}",
						applicationId, e.getMessage());
				return store.current(userId, applicationId,
						"문항 생성에 실패해 기본 문항을 유지했습니다. 잠시 후 다시 시도할 수 있습니다.");
			}

			List<EssayQuestionPromptBuilder.GeneratedQuestion> generated =
					promptBuilder.parse(raw, sourceText);
			if (generated.isEmpty()) {
				// 응답이 비었든 근거 검증에서 다 걸렀든, 결과는 같다 — 기본 문항을 지킨다.
				log.info("근거 있는 맞춤 문항을 만들지 못해 기본 문항을 유지합니다. applicationId={}",
						applicationId);
				return store.current(userId, applicationId,
						"공고에서 문항 근거를 찾지 못해 기본 문항을 유지했습니다.");
			}
			return store.replace(userId, applicationId, generated);
		} finally {
			// 내가 잡은 잠금만 해제한다. TTL 이 만료돼 다른 요청이 새로 잡았다면 건드리지 않는다.
			redisLock.unlock(LOCK_KEY + applicationId, lockToken.get());
		}
	}

	/** 사용자별 생성 횟수를 제한한다. 지원서 ID 를 순회해도 크레딧이 무한정 나가지 않게 한다. */
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
			log.warn("문항 생성 한도 초과. userId={}, used={}", userId, used);
			throw new CustomException(ErrorCode.ESSAY_QUESTION_QUOTA_EXCEEDED);
		}
	}
}
