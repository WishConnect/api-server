package com.wishconnect.domain.application.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.application.config.LlmConfig;
import com.wishconnect.domain.application.config.LlmProperties;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 용도별 키 분리: 자소서·인터뷰는 자소서 키, 데이터 처리는 파싱 키로 나가는지 본다. */
class AnthropicLlmClientRoutingTest {

	@ParameterizedTest
	@EnumSource(LlmModel.class)
	void 용도에_맞는_키의_클라이언트로_보낸다(LlmModel model) {
		AnthropicClient essay = mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
		AnthropicClient parser = mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
		AnthropicLlmClient client = new AnthropicLlmClient(
				new LlmConfig.AnthropicClients(essay, parser),
				new LlmProperties(null, null, null, null, null));

		try {
			client.chat(LlmChatRequest.of(model, "system", List.of(LlmMessage.user("hi"))));
		} catch (RuntimeException ignored) {
			// 딥 스텁 응답은 비어 있어 실패로 끝난다. 어느 클라이언트를 불렀는지만 본다.
		}

		boolean essayUse = model == LlmModel.INTERVIEW || model == LlmModel.DRAFT;
		AnthropicClient used = essayUse ? essay : parser;
		AnthropicClient unused = essayUse ? parser : essay;
		verify(used.messages()).create(any(MessageCreateParams.class));
		verify(unused.messages(), never()).create(any(MessageCreateParams.class));
	}
}
