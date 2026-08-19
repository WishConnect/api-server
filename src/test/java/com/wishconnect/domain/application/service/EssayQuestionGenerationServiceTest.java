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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.dto.response.EssayQuestionGenerationResponse;
import com.wishconnect.domain.application.dto.response.EssayQuestionGenerationResponse.Source;
import com.wishconnect.domain.application.service.prompt.EssayQuestionPromptBuilder;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.wishconnect.global.lock.RedisLock;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 맞춤 문항 생성 흐름 검증.
 *
 * <p>가장 중요한 성질은 <b>"실패해도 지원서를 못 쓰게 되지 않는다"</b> 이다. 지원서 생성과 분리한
 * 이유가 그것이므로, LLM 이 죽든 응답이 이상하든 근거가 없든 기본 문항으로 계속 쓸 수 있어야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EssayQuestionGenerationServiceTest {

	private static final UUID USER_ID = UUID.randomUUID();
	private static final Long APPLICATION_ID = 100L;

	private static final String SOURCE_TEXT = """
			지역인재 장학금 경희대학교
			신청자격 : 경기도에 주민등록을 둔 자로서 직전학기 평점 3.5 이상인 자
			선발기준 : 학업계획서 평가 50%
			""";

	private static final String GOOD_RESPONSE = """
			[{"title":"지역 연고","description":"경기도와의 인연을 서술해주세요.","charLimit":600,
			  "basis":"경기도에 주민등록을 둔 자"},
			 {"title":"학업 계획","description":"앞으로의 학업 계획을 서술해주세요.","charLimit":800,
			  "basis":"학업계획서 평가 50%"}]
			""";

	@Mock private EssayQuestionStore store;
	@Mock private LlmClient llmClient;
	@Mock private RedisLock redisLock;
	@Mock private StringRedisTemplate redisTemplate;
	@Mock private ValueOperations<String, String> valueOperations;

	private EssayQuestionGenerationService service;

	@BeforeEach
	void setUp() {
		service = new EssayQuestionGenerationService(store,
				new EssayQuestionPromptBuilder(new ObjectMapper()), llmClient,
				redisLock, redisTemplate);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(redisLock.tryLock(anyString(), any(Duration.class)))
				.willReturn(Optional.of("token"));
		given(valueOperations.increment(anyString())).willReturn(1L);
		given(store.prepare(USER_ID, APPLICATION_ID))
				.willReturn(new EssayQuestionStore.Prepared(scholarship(), List.of(), null));
		given(store.current(any(), anyLong(), any()))
				.willAnswer(i -> response(Source.DEFAULT, i.getArgument(2)));
		given(store.replace(any(), anyLong(), any()))
				.willAnswer(i -> response(Source.GENERATED, null));
	}

	// --- 정상 생성 ---

	@Test
	@DisplayName("근거 있는 문항이 나오면 교체한다")
	void replacesWhenGrounded() {
		given(llmClient.chat(any())).willReturn(GOOD_RESPONSE);

		EssayQuestionGenerationResponse result = service.generate(USER_ID, APPLICATION_ID);

		assertThat(result.source()).isEqualTo(Source.GENERATED);
		verify(store).replace(eq(USER_ID), eq(APPLICATION_ID), any());
	}

	// --- 폴백: 어떤 경우에도 기본 문항이 남아야 한다 ---

	@Test
	@DisplayName("근거 없는 문항뿐이면 교체하지 않고 기본 문항을 지킨다")
	void keepsDefaultWhenUngrounded() {
		given(llmClient.chat(any())).willReturn("""
				[{"title":"봉사 경험","description":"봉사활동 경험을 서술해주세요.","charLimit":600,
				  "basis":"봉사활동 실적이 우수한 자"},
				 {"title":"수상 실적","description":"수상 실적을 서술해주세요.","charLimit":600,
				  "basis":"교외 수상 실적이 있는 자"}]
				""");

		EssayQuestionGenerationResponse result = service.generate(USER_ID, APPLICATION_ID);

		assertThat(result.source()).isEqualTo(Source.DEFAULT);
		assertThat(result.reason()).contains("근거");
		verify(store, never()).replace(any(), anyLong(), any());
	}

	@Test
	@DisplayName("LLM 이 빈 배열을 주면 기본 문항을 지킨다")
	void keepsDefaultOnEmptyArray() {
		given(llmClient.chat(any())).willReturn("[]");

		assertThat(service.generate(USER_ID, APPLICATION_ID).source()).isEqualTo(Source.DEFAULT);
		verify(store, never()).replace(any(), anyLong(), any());
	}

	@Test
	@DisplayName("LLM 응답이 JSON 이 아니어도 기본 문항을 지킨다")
	void keepsDefaultOnMalformedResponse() {
		given(llmClient.chat(any())).willReturn("죄송합니다. 공고 정보가 부족합니다.");

		assertThat(service.generate(USER_ID, APPLICATION_ID).source()).isEqualTo(Source.DEFAULT);
	}

	@Test
	@DisplayName("LLM 호출이 통째로 실패해도 200 으로 기본 문항을 돌려준다 — 지원서를 못 쓰게 되면 안 된다")
	void keepsDefaultWhenLlmThrows() {
		given(llmClient.chat(any())).willThrow(new RuntimeException("credit balance too low"));

		EssayQuestionGenerationResponse result = service.generate(USER_ID, APPLICATION_ID);

		assertThat(result.source()).isEqualTo(Source.DEFAULT);
		assertThat(result.reason()).contains("실패");
	}

	@Test
	@DisplayName("LLM 실패가 CustomException 으로 와도 폴백한다 — LlmClient 는 실패를 이렇게 감싼다")
	void keepsDefaultWhenLlmThrowsCustomException() {
		// 실제로 이 경로가 빠져 있어 LLM 서버를 내렸을 때 500 이 나갔다.
		// 제어 신호(404·409·429)를 다시 던지려고 CustomException 을 통과시켰더니
		// LLM 실패까지 함께 통과해 버린 것이다.
		given(llmClient.chat(any()))
				.willThrow(new CustomException(ErrorCode.LLM_CALL_FAILED));

		EssayQuestionGenerationResponse result = service.generate(USER_ID, APPLICATION_ID);

		assertThat(result.source()).isEqualTo(Source.DEFAULT);
		verify(store, never()).replace(any(), anyLong(), any());
	}

	// --- 작성 중 보호 ---

	@Test
	@DisplayName("이미 작성을 시작했으면 409 로 막고 LLM 도 부르지 않는다")
	void rejectsWhenWritingStarted() {
		given(store.prepare(USER_ID, APPLICATION_ID))
				.willThrow(new CustomException(ErrorCode.ESSAY_QUESTIONS_LOCKED));

		assertThatThrownBy(() -> service.generate(USER_ID, APPLICATION_ID))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ESSAY_QUESTIONS_LOCKED);

		verify(llmClient, never()).chat(any());
	}

	@Test
	@DisplayName("없는 지원서면 404 를 그대로 올린다 — 폴백으로 감추지 않는다")
	void propagatesNotFound() {
		given(store.prepare(USER_ID, APPLICATION_ID))
				.willThrow(new CustomException(ErrorCode.APPLICATION_NOT_FOUND));

		assertThatThrownBy(() -> service.generate(USER_ID, APPLICATION_ID))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.APPLICATION_NOT_FOUND);
	}

	// --- 동시성·비용 ---

	@Test
	@DisplayName("잠금을 못 잡으면 LLM 을 부르지 않고 현재 문항을 돌려준다")
	void skipsWhenLocked() {
		given(redisLock.tryLock(anyString(), any(Duration.class))).willReturn(Optional.empty());

		EssayQuestionGenerationResponse result = service.generate(USER_ID, APPLICATION_ID);

		assertThat(result.source()).isEqualTo(Source.DEFAULT);
		verify(llmClient, never()).chat(any());
		verify(store, never()).replace(any(), anyLong(), any());
	}

	@Test
	@DisplayName("한도를 넘기면 LLM 을 부르지 않고 429 로 막는다")
	void enforcesQuota() {
		given(valueOperations.increment(anyString())).willReturn(21L);

		assertThatThrownBy(() -> service.generate(USER_ID, APPLICATION_ID))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ESSAY_QUESTION_QUOTA_EXCEEDED);

		verify(llmClient, never()).chat(any());
	}

	@Test
	@DisplayName("성공하든 실패하든 잠금을 푼다 — 내가 받은 토큰으로만 푼다")
	void releasesLockWithOwnToken() {
		given(redisLock.tryLock(anyString(), any(Duration.class)))
				.willReturn(Optional.of("my-token"));
		given(llmClient.chat(any())).willThrow(new RuntimeException("boom"));

		service.generate(USER_ID, APPLICATION_ID);

		// 토큰 없이 지우면 TTL 만료 뒤 남의 잠금을 지운다.
		verify(redisLock).unlock("essay-question:lock:" + APPLICATION_ID, "my-token");
	}

	// --- 재호출 멱등성 ---

	@Test
	@DisplayName("이미 맞춤 문항으로 교체된 지원서는 다시 만들지 않는다 — LLM·한도·questionId 모두 그대로")
	void isIdempotentAfterSuccess() {
		// 재시도·더블클릭·화면 재진입으로 같은 API 가 또 불리는 상황.
		given(store.prepare(USER_ID, APPLICATION_ID)).willReturn(
				new EssayQuestionStore.Prepared(null, List.of(), response(Source.GENERATED, null)));

		EssayQuestionGenerationResponse result = service.generate(USER_ID, APPLICATION_ID);

		assertThat(result.source()).isEqualTo(Source.GENERATED);
		verify(llmClient, never()).chat(any());
		verify(store, never()).replace(any(), anyLong(), any());
		verify(valueOperations, never()).increment(anyString());
		verify(redisLock, never()).tryLock(anyString(), any(Duration.class));
	}

	@Test
	@DisplayName("LLM 호출 전에는 교체하지 않는다 — 교체 트랜잭션이 LLM 지연을 덮으면 안 된다")
	void replacesOnlyAfterLlmReturns() {
		given(llmClient.chat(any())).willAnswer(i -> {
			verify(store, never()).replace(any(), anyLong(), any());
			return GOOD_RESPONSE;
		});

		service.generate(USER_ID, APPLICATION_ID);

		verify(store).replace(eq(USER_ID), eq(APPLICATION_ID), any());
	}

	// --- fixture ---

	private EssayQuestionGenerationResponse response(Source source, String reason) {
		return new EssayQuestionGenerationResponse(source,
				List.of(new EssayQuestionGenerationResponse.Item(1L, 1, "지원 동기", "서술해주세요.", 500)),
				reason);
	}

	private Scholarship scholarship() {
		Scholarship scholarship = Scholarship.builder()
				.title("지역인재 장학금")
				.provider("경희대학교")
				.summary("경기도 출신 학생의 학업을 지원합니다.")
				.description(SOURCE_TEXT)
				.scholarshipType(ScholarshipType.INTERNAL)
				.primarySource("UNIV_KHU")
				.dedupKey("key-1")
				.build();
		setField(scholarship, "id", 1L);
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
