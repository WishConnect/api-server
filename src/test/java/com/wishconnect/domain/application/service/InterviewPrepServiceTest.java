package com.wishconnect.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.application.dto.response.InterviewPrepResponse;
import com.wishconnect.domain.application.service.prompt.InterviewPrepPromptBuilder;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 면접 예상 질문 생성 흐름 검증.
 *
 * <p>지키려는 것은 넷이다.
 * <ul>
 *   <li><b>LLM 을 필요할 때만 부르는가</b> — 조회는 절대, 이미 있으면 절대, 동시 요청이면 한 번만.</li>
 *   <li><b>동시 요청이 실패로 끝나지 않는가</b> — 유니크 충돌은 500 이 아니라 같은 결과여야 한다.</li>
 *   <li><b>비용 한도가 걸리는가</b> — 로그인만 하면 부를 수 있는 API 다.</li>
 *   <li><b>LLM 호출 동안 DB 트랜잭션을 잡지 않는가</b> — 저장은 호출 뒤에만 일어나야 한다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewPrepServiceTest {

	private static final UUID USER_ID = UUID.randomUUID();
	private static final Long SCHOLARSHIP_ID = 1L;

	private static final String LLM_RESPONSE = """
			1. 이 장학금에 지원한 이유는 무엇인가요? | 장학금 취지 이해도를 봅니다.
			2. 학업 중 가장 어려웠던 순간과 대응을 말씀해주세요. | 문제 해결 태도를 봅니다.
			3. 수혜 후 계획은 무엇인가요? | 지속성과 기여 의지를 봅니다.
			""";

	@Mock private InterviewPrepStore store;
	@Mock private LlmClient llmClient;
	@Mock private StringRedisTemplate redisTemplate;
	@Mock private ValueOperations<String, String> valueOperations;

	private InterviewPrepService service;

	@BeforeEach
	void setUp() {
		service = new InterviewPrepService(store, new InterviewPrepPromptBuilder(),
				llmClient, redisTemplate);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		// 기본값: 잠금 획득 성공, 한도 여유 있음
		given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
				.willReturn(true);
		given(valueOperations.increment(anyString())).willReturn(1L);
		given(store.prepare(SCHOLARSHIP_ID)).willReturn(needsGeneration());
		given(store.find(SCHOLARSHIP_ID)).willReturn(empty());
		given(store.save(anyLong(), any())).willAnswer(i -> generated(i.getArgument(1)));
	}

	// --- 생성 ---

	@Test
	@DisplayName("질문이 없으면 생성해 저장한다")
	void generatesQuestions() {
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);

		InterviewPrepResponse response = service.generate(USER_ID, SCHOLARSHIP_ID);

		assertThat(response.totalCount()).isEqualTo(3);
		verify(store).save(eq(SCHOLARSHIP_ID), any());
	}

	@Test
	@DisplayName("이미 질문이 있으면 LLM 도 저장도 하지 않는다")
	void doesNotRegenerate() {
		given(store.prepare(SCHOLARSHIP_ID))
				.willReturn(new InterviewPrepStore.Prepared(scholarship(), List.of(), filled(6)));

		InterviewPrepResponse response = service.generate(USER_ID, SCHOLARSHIP_ID);

		verify(llmClient, never()).chat(any());
		verify(store, never()).save(anyLong(), any());
		assertThat(response.totalCount()).isEqualTo(6);
	}

	@Test
	@DisplayName("조회는 LLM 을 절대 부르지 않는다 — 화면을 여는 것만으로 크레딧이 나가면 안 된다")
	void getNeverCallsLlm() {
		service.get(SCHOLARSHIP_ID);

		verify(llmClient, never()).chat(any());
		verify(store).find(SCHOLARSHIP_ID);
	}

	@Test
	@DisplayName("LLM 이 질문을 하나도 만들지 못하면 저장하지 않고 실패로 알린다")
	void failsWhenNothingGenerated() {
		given(llmClient.chat(any())).willReturn("   ");

		assertThatThrownBy(() -> service.generate(USER_ID, SCHOLARSHIP_ID))
				.isInstanceOf(CustomException.class);

		verify(store, never()).save(anyLong(), any());
	}

	@Test
	@DisplayName("면접 예상 질문은 Haiku 로 만든다")
	void usesInterviewModel() {
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);

		service.generate(USER_ID, SCHOLARSHIP_ID);

		ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
		verify(llmClient).chat(captor.capture());
		assertThat(captor.getValue().model()).isEqualTo(LlmModel.INTERVIEW);
	}

	// --- 동시 생성 ---

	@Test
	@DisplayName("잠금을 못 잡으면 LLM 을 부르지 않고 먼저 만들어진 질문을 받는다")
	void waitsInsteadOfCallingLlmTwice() {
		given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
				.willReturn(false);
		// 기다리는 사이 다른 요청이 저장을 마친다.
		AtomicInteger calls = new AtomicInteger();
		given(store.find(SCHOLARSHIP_ID))
				.willAnswer(i -> calls.incrementAndGet() >= 2 ? filled(6) : empty());

		InterviewPrepResponse response = service.generate(USER_ID, SCHOLARSHIP_ID);

		verify(llmClient, never()).chat(any());
		verify(store, never()).save(anyLong(), any());
		assertThat(response.totalCount()).isEqualTo(6);
	}

	@Test
	@DisplayName("끝까지 기다려도 없으면 빈 목록을 준다 — 오류로 만들지 않는다")
	void returnsEmptyWhenPeerNeverFinishes() {
		given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
				.willReturn(false);

		InterviewPrepResponse response = service.generate(USER_ID, SCHOLARSHIP_ID);

		assertThat(response.totalCount()).isZero();
		verify(llmClient, never()).chat(any());
	}

	@Test
	@DisplayName("잠금을 잡은 사이 다른 요청이 끝냈으면 LLM 을 부르지 않는다")
	void skipsLlmWhenFilledBetweenPrepareAndLock() {
		given(store.find(SCHOLARSHIP_ID)).willReturn(filled(6));

		InterviewPrepResponse response = service.generate(USER_ID, SCHOLARSHIP_ID);

		verify(llmClient, never()).chat(any());
		assertThat(response.totalCount()).isEqualTo(6);
	}

	@Test
	@DisplayName("저장이 유니크 제약에 걸리면 500 이 아니라 이미 저장된 질문을 준다")
	void treatsUniqueViolationAsSuccess() {
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);
		// given(store.save(..)) 형태로 다시 스텁하면 기존 스텁이 null 인자로 실행돼 NPE 가 난다.
		// 메서드를 호출하지 않는 willThrow(..).given(..) 형태를 쓴다.
		org.mockito.BDDMockito.willThrow(new InterviewPrepStore.AlreadySavedException())
				.given(store).save(anyLong(), any());
		AtomicInteger calls = new AtomicInteger();
		given(store.find(SCHOLARSHIP_ID))
				.willAnswer(i -> calls.incrementAndGet() >= 2 ? filled(6) : empty());

		InterviewPrepResponse response = service.generate(USER_ID, SCHOLARSHIP_ID);

		assertThat(response.totalCount()).isEqualTo(6);
	}

	@Test
	@DisplayName("성공하든 실패하든 잠금을 푼다 — 안 풀면 그 장학금이 90초간 막힌다")
	void releasesLockOnFailure() {
		given(llmClient.chat(any())).willThrow(new RuntimeException("credit balance too low"));

		assertThatThrownBy(() -> service.generate(USER_ID, SCHOLARSHIP_ID))
				.isInstanceOf(RuntimeException.class);

		verify(redisTemplate).delete("interview-prep:lock:" + SCHOLARSHIP_ID);
	}

	// --- 비용 ---

	@Test
	@DisplayName("한도를 넘기면 LLM 을 부르지 않고 429 로 막는다")
	void enforcesQuota() {
		given(valueOperations.increment(anyString())).willReturn(11L);

		assertThatThrownBy(() -> service.generate(USER_ID, SCHOLARSHIP_ID))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INTERVIEW_PREP_QUOTA_EXCEEDED);

		verify(llmClient, never()).chat(any());
	}

	@Test
	@DisplayName("이미 만들어진 질문을 받는 호출은 한도를 쓰지 않는다")
	void doesNotConsumeQuotaWhenCached() {
		given(store.prepare(SCHOLARSHIP_ID))
				.willReturn(new InterviewPrepStore.Prepared(scholarship(), List.of(), filled(6)));

		service.generate(USER_ID, SCHOLARSHIP_ID);

		verify(valueOperations, never()).increment(anyString());
	}

	@Test
	@DisplayName("첫 사용에만 만료 시간을 건다 — 매번 걸면 창이 계속 밀려 제한이 무의미해진다")
	void setsQuotaExpiryOnlyOnFirstUse() {
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);
		given(valueOperations.increment(anyString())).willReturn(3L);

		service.generate(USER_ID, SCHOLARSHIP_ID);

		verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
	}

	// --- 트랜잭션 분리 ---

	@Test
	@DisplayName("LLM 호출 전에는 저장하지 않는다 — 저장 트랜잭션이 LLM 지연을 덮으면 안 된다")
	void savesOnlyAfterLlmReturns() {
		given(llmClient.chat(any())).willAnswer(i -> {
			verify(store, never()).save(anyLong(), any());
			return LLM_RESPONSE;
		});

		service.generate(USER_ID, SCHOLARSHIP_ID);

		verify(store).save(eq(SCHOLARSHIP_ID), any());
	}

	// --- 재생성 ---

	@Test
	@DisplayName("관리자 삭제는 저장소에 그대로 위임한다")
	void clearDelegates() {
		service.clear(SCHOLARSHIP_ID);

		verify(store).clear(SCHOLARSHIP_ID);
	}

	// --- fixture ---

	private InterviewPrepStore.Prepared needsGeneration() {
		return new InterviewPrepStore.Prepared(scholarship(), List.of(), null);
	}

	private InterviewPrepResponse empty() {
		return InterviewPrepResponse.of(List.of(), RequirementLevel.CONDITIONAL, "면접 진행");
	}

	private InterviewPrepResponse filled(int count) {
		return new InterviewPrepResponse(
				java.util.stream.IntStream.range(0, count)
						.mapToObj(i -> new InterviewPrepResponse.Item(i, "질문 " + i + " 인가요?", null))
						.toList(),
				count, RequirementLevel.CONDITIONAL, "면접 진행");
	}

	private InterviewPrepResponse generated(List<InterviewPrepPromptBuilder.Generated> items) {
		if (items == null) {
			return empty();
		}
		return new InterviewPrepResponse(
				java.util.stream.IntStream.range(0, items.size())
						.mapToObj(i -> new InterviewPrepResponse.Item(
								i, items.get(i).questionText(), items.get(i).intent()))
						.toList(),
				items.size(), RequirementLevel.CONDITIONAL, "면접 진행");
	}

	private Scholarship scholarship() {
		Scholarship scholarship = Scholarship.builder()
				.title("가계곤란 장학금")
				.provider("경희대학교")
				.summary("가계 형편이 어려운 재학생을 지원합니다.")
				.scholarshipType(ScholarshipType.INTERNAL)
				.primarySource("UNIV_KHU")
				.dedupKey("key-1")
				.build();
		setField(scholarship, "id", SCHOLARSHIP_ID);
		setField(scholarship, "interviewRequirement", RequirementLevel.CONDITIONAL);
		return scholarship;
	}

	private static void setField(Object target, String name, Object value) {
		try {
			Class<?> current = target.getClass();
			while (current != null && current != Object.class) {
				try {
					Field field = current.getDeclaredField(name);
					field.setAccessible(true);
					field.set(target, value);
					return;
				} catch (NoSuchFieldException ignored) {
					current = current.getSuperclass();
				}
			}
			throw new NoSuchFieldException(name);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
