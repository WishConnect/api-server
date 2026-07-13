package com.wishconnect.domain.application.client.dto;

/**
 * 멀티턴 대화 이력의 한 발화. Claude Messages API의 user/assistant 턴에 대응된다.
 */
public record LlmMessage(Role role, String content) {

	public enum Role {
		USER,
		ASSISTANT
	}

	public static LlmMessage user(String content) {
		return new LlmMessage(Role.USER, content);
	}

	public static LlmMessage assistant(String content) {
		return new LlmMessage(Role.ASSISTANT, content);
	}
}
