package com.wishconnect.domain.application.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.application.config.LlmProperties;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
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

	private String resolveModelId(LlmModel model) {
		return switch (model) {
			case INTERVIEW -> properties.interviewModel();
			case DRAFT -> properties.draftModel();
		};
	}
}
