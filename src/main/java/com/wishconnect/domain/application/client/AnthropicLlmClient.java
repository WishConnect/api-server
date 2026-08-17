package com.wishconnect.domain.application.client;

import com.anthropic.core.JsonValue;
import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.application.config.LlmProperties;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Anthropic Claude API 기반 {@link LlmClient} 구현체.
 * <p>
 * INTERVIEW/DRAFT 목적에 따라 {@link LlmProperties} 의 서로 다른 모델 ID로 요청을 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicLlmClient implements LlmClient {

	private final AnthropicClient anthropicClient;
	private final LlmProperties properties;

	@Override
	public String chat(LlmChatRequest request) {
		String modelId = resolveModelId(request.model());
		long maxTokens = request.maxTokensOverride() != null
				? request.maxTokensOverride()
				: properties.maxTokens();

		MessageCreateParams.Builder builder = MessageCreateParams.builder()
				.model(modelId)
				.maxTokens(maxTokens);

		if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
			builder.system(request.systemPrompt());
		}
		if (request.outputSchema() != null && !request.outputSchema().isEmpty()) {
			builder.outputConfig(toOutputConfig(request.outputSchema()));
		}

		for (LlmMessage message : request.messages()) {
			if (message.role() == LlmMessage.Role.USER) {
				builder.addUserMessage(message.content());
			} else {
				builder.addAssistantMessage(message.content());
			}
		}

		try {
			Message response = anthropicClient.messages().create(builder.build());
			String text = response.content().stream()
					.flatMap(block -> block.text().stream())
					.map(textBlock -> textBlock.text())
					.collect(Collectors.joining());

			/*
			max_tokens 로 끊긴 응답은 JSON 이 중간에 잘려 파싱이 반드시 실패한다.
			같은 요청을 재시도해도 같은 지점에서 잘리므로, 일반 실패와 구분해 올려
			호출측이 재시도 대상에서 빼고 max_tokens 를 올릴 수 있게 한다.
			 */
			if (response.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
				log.warn("[LLM] 응답이 max_tokens({})에서 잘렸습니다. model={}", maxTokens, modelId);
				throw new CustomException(ErrorCode.LLM_RESPONSE_TRUNCATED);
			}
			if (text.isBlank()) {
				log.warn("LLM 응답 텍스트가 비어 있습니다. model={}, stopReason={}",
						modelId, response.stopReason());
				throw new CustomException(ErrorCode.LLM_EMPTY_RESPONSE);
			}
			return text;
		} catch (CustomException e) {
			throw e;
		} catch (Exception e) {
			log.error("LLM 호출 실패. model={}, error={}", modelId, e.getMessage(), e);
			throw new CustomException(ErrorCode.LLM_CALL_FAILED);
		}
	}

	/**
	 * JSON Schema 를 응답 형식 제약으로 변환한다.
	 *
	 * <p>{@code Schema} 는 SDK 상 자유 맵이라 스키마 본문을 그대로 실어 보낸다.
	 */
	private OutputConfig toOutputConfig(Map<String, Object> schema) {
		JsonOutputFormat.Schema.Builder schemaBuilder = JsonOutputFormat.Schema.builder();
		schema.forEach((key, value) -> schemaBuilder.putAdditionalProperty(key, JsonValue.from(value)));
		return OutputConfig.builder()
				.format(JsonOutputFormat.builder().schema(schemaBuilder.build()).build())
				.build();
	}

	private String resolveModelId(LlmModel model) {
		return switch (model) {
			case INTERVIEW -> properties.interviewModel();
			case DRAFT -> properties.draftModel();
			case SUMMARY -> properties.summaryModel();
			case PARSING -> properties.parserModel();
		};
	}
}
