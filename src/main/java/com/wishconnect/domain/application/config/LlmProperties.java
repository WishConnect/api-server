package com.wishconnect.domain.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM(Anthropic) 호출 설정.
 * <p>
 * - interviewModel: STEP1 사전 인터뷰 세부 질문 생성용 (짧은 응답이 대부분, 저가 모델 권장)
 * - draftModel: STEP2 자기소개서 초안 생성용 (장문·문체 품질 중요)
 * - parserModel: 대학 장학공지 본문 파싱용 (건수가 많아 저가 모델 필수)
 * - maxTokens: 응답 최대 토큰 기본값 (요청별로 override 가능)
 * <p>
 * API 키는 {@code ANTHROPIC_API_KEY} 환경변수로 주입한다.
 */
@ConfigurationProperties(prefix = "llm.anthropic")
public record LlmProperties(
		String interviewModel,
		String draftModel,
		String summaryModel,
		String parserModel,
		Integer maxTokens
) {

	public LlmProperties {
		if (interviewModel == null || interviewModel.isBlank()) {
			interviewModel = "claude-haiku-4-5";
		}
		if (draftModel == null || draftModel.isBlank()) {
			draftModel = "claude-sonnet-5";
		}
		if (summaryModel == null || summaryModel.isBlank()) {
			summaryModel = "claude-haiku-4-5";
		}
		if (parserModel == null || parserModel.isBlank()) {
			parserModel = "claude-haiku-4-5";
		}
		if (maxTokens == null || maxTokens <= 0) {
			maxTokens = 4096;
		}
	}
}
