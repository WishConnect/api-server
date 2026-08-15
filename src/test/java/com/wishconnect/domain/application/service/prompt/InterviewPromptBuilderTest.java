package com.wishconnect.domain.application.service.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 사전 질문 일괄 생성 응답 파싱 검증.
 * LLM 이 형식을 어기는 경우까지 방어하는지가 핵심이다.
 */
class InterviewPromptBuilderTest {

	private final InterviewPromptBuilder builder = new InterviewPromptBuilder();

	@Test
	@DisplayName("번호 목록 형식을 파싱한다")
	void parsesNumberedList() {
		List<String> questions = builder.parseQuestions("""
				1. 어떤 경험이 가장 기억에 남나요?
				2. 그때 어떤 판단을 했나요?
				3. 어떤 어려움이 있었나요?
				4. 무엇을 배웠나요?
				5. 앞으로의 계획은 무엇인가요?
				""");

		assertThat(questions).containsExactly(
				"어떤 경험이 가장 기억에 남나요?",
				"그때 어떤 판단을 했나요?",
				"어떤 어려움이 있었나요?",
				"무엇을 배웠나요?",
				"앞으로의 계획은 무엇인가요?");
	}

	@Test
	@DisplayName("괄호 번호와 머리말·빈 줄이 섞여도 질문만 뽑는다")
	void ignoresPreambleAndBlankLines() {
		List<String> questions = builder.parseQuestions("""
				아래는 사전 질문입니다.

				1) 첫 질문

				2) 둘째 질문
				""");

		assertThat(questions).containsExactly("첫 질문", "둘째 질문");
	}

	@Test
	@DisplayName("번호가 없으면 비어있지 않은 줄을 그대로 질문으로 쓴다")
	void fallsBackToPlainLines() {
		List<String> questions = builder.parseQuestions("""
				첫 질문
				둘째 질문
				""");

		assertThat(questions).containsExactly("첫 질문", "둘째 질문");
	}

	@Test
	@DisplayName("요청 개수를 초과해 생성하면 앞에서부터 잘라낸다")
	void truncatesToQuestionCount() {
		StringBuilder response = new StringBuilder();
		for (int i = 1; i <= 8; i++) {
			response.append(i).append(". 질문").append(i).append("\n");
		}

		List<String> questions = builder.parseQuestions(response.toString());

		assertThat(questions).hasSize(InterviewPromptBuilder.QUESTION_COUNT);
		assertThat(questions.get(0)).isEqualTo("질문1");
		assertThat(questions.get(4)).isEqualTo("질문5");
	}

	@Test
	@DisplayName("null·공백 응답은 빈 목록을 반환한다")
	void handlesEmptyResponse() {
		assertThat(builder.parseQuestions(null)).isEmpty();
		assertThat(builder.parseQuestions("   \n  ")).isEmpty();
	}
}
