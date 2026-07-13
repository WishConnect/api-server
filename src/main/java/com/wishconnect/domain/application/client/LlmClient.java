package com.wishconnect.domain.application.client;

import com.wishconnect.domain.application.client.dto.LlmChatRequest;

/**
 * LLM 호출 추상화.
 * <p>
 * AI 자기소개서 파트에서 STEP1 사전 인터뷰 세부 질문 생성과 STEP2 자기소개서 초안 생성에 사용된다.
 * 벤더 교체, 테스트 mock 주입 등을 위해 인터페이스로 분리한다.
 */
public interface LlmClient {

	/**
	 * 시스템 프롬프트 + 대화 이력을 기반으로 텍스트 응답을 생성한다.
	 *
	 * @param request 모델, 시스템 프롬프트, 대화 이력
	 * @return 응답 텍스트 (공백 아님)
	 */
	String chat(LlmChatRequest request);
}
