package com.wishconnect.domain.application.client.dto;

import java.util.List;
import java.util.Map;

/**
 * LLM 호출 요청.
 *
 * @param model             호출 목적(모델 프로필)
 * @param systemPrompt      시스템 프롬프트 (nullable)
 * @param messages          대화 이력. Claude API 규약상 user/assistant가 교대로 나타나야 한다.
 * @param maxTokensOverride 요청별 max_tokens 오버라이드 (null이면 LlmProperties.maxTokens 사용)
 * @param outputSchema      응답을 강제할 JSON Schema (nullable). 지정하면 모델이 이 형식에서
 *                          벗어난 응답을 낼 수 없다 — 코드펜스·앞뒤 설명·타입 불일치가 원천 차단된다.
 */
public record LlmChatRequest(
		LlmModel model,
		String systemPrompt,
		List<LlmMessage> messages,
		Integer maxTokensOverride,
		Map<String, Object> outputSchema
) {

	public LlmChatRequest {
		if (model == null) {
			throw new IllegalArgumentException("LlmModel은 필수입니다.");
		}
		if (messages == null || messages.isEmpty()) {
			throw new IllegalArgumentException("messages는 최소 1개 이상이어야 합니다.");
		}
	}

	public static LlmChatRequest of(LlmModel model, String systemPrompt, List<LlmMessage> messages) {
		return new LlmChatRequest(model, systemPrompt, messages, null, null);
	}

	/** 응답 형식을 JSON Schema 로 강제하는 요청. */
	public static LlmChatRequest structured(LlmModel model, String systemPrompt,
			List<LlmMessage> messages, Integer maxTokensOverride, Map<String, Object> outputSchema) {
		return new LlmChatRequest(model, systemPrompt, messages, maxTokensOverride, outputSchema);
	}
}
