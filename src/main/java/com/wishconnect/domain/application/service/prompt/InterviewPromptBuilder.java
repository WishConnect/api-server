package com.wishconnect.domain.application.service.prompt;

import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.application.entity.EssayQuestion;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * STEP1 사전 인터뷰용 LLM 프롬프트 조립기.
 *
 * <p>문항(카테고리) 하나당 사전 질문 {@value #QUESTION_COUNT}개를 <b>한 번의 호출로 일괄 생성</b>한다.
 * 이전에는 답변을 읽고 꼬리질문을 만드는 대화형이었으나, 질문을 한 화면에 모두 노출하는 UX로
 * 변경되면서 일괄 생성 방식이 되었다. LLM 호출 횟수가 문항당 최대 5회에서 1회로 줄어든다.
 *
 * <p>꼬리질문이 사라진 만큼 질문끼리 각도가 겹치면 초안 재료가 얕아지므로, 프롬프트에서
 * 서로 다른 관점(경험·행동·역경·배움·계획)을 다루도록 명시한다.
 */
@Component
public class InterviewPromptBuilder {

	/** 문항(카테고리)당 생성할 사전 질문 개수. */
	public static final int QUESTION_COUNT = 5;

	/** 질문 한 줄의 최대 길이. 이보다 길면 화면에서 잘리므로 프롬프트로 제한한다. */
	private static final int MAX_QUESTION_LENGTH = 60;

	/** "1. 질문" / "2) 질문" 형태의 번호 목록 한 줄. */
	private static final Pattern NUMBERED_LINE = Pattern.compile("^\\s*(\\d{1,2})\\s*[.)]\\s*(.+)$");

	private static final String KICKOFF_USER_MESSAGE = "사전 질문을 만들어주세요.";

	/**
	 * 문항 하나에 대한 사전 질문 일괄 생성 요청을 조립한다.
	 *
	 * @param scholarship 대상 장학금
	 * @param question    질문을 생성할 문항(카테고리)
	 */
	public LlmChatRequest build(Scholarship scholarship, EssayQuestion question) {
		String systemPrompt = buildSystemPrompt(scholarship, question);
		return LlmChatRequest.of(LlmModel.INTERVIEW, systemPrompt,
				List.of(LlmMessage.user(KICKOFF_USER_MESSAGE)));
	}

	/**
	 * LLM 응답에서 질문 목록을 추출한다.
	 *
	 * <p>번호 목록을 우선 파싱하고, 모델이 형식을 어겨 번호가 없으면 비어 있지 않은 줄을
	 * 그대로 질문으로 사용한다. 요청 개수를 넘겨 생성했다면 앞에서부터 잘라낸다.
	 *
	 * @return 추출된 질문 목록. 하나도 뽑지 못하면 빈 리스트
	 */
	public List<String> parseQuestions(String response) {
		if (response == null || response.isBlank()) {
			return List.of();
		}

		List<String> questions = new ArrayList<>();
		for (String line : response.split("\\R")) {
			Matcher matcher = NUMBERED_LINE.matcher(line);
			if (matcher.matches()) {
				addIfNotBlank(questions, matcher.group(2));
			}
		}

		if (questions.isEmpty()) {
			for (String line : response.split("\\R")) {
				addIfNotBlank(questions, line);
			}
		}

		return questions.size() > QUESTION_COUNT
				? List.copyOf(questions.subList(0, QUESTION_COUNT))
				: List.copyOf(questions);
	}

	private void addIfNotBlank(List<String> target, String candidate) {
		String trimmed = candidate.trim();
		if (!trimmed.isEmpty()) {
			target.add(trimmed);
		}
	}

	private String buildSystemPrompt(Scholarship scholarship, EssayQuestion question) {
		return """
				당신은 대학생의 장학금 지원 자기소개서 작성을 돕는 인터뷰어입니다.

				<context>
				장학금: %s (%s)
				문항: %s
				문항 설명: %s
				</context>

				과제:
				위 문항의 자기소개서를 쓰기 위해 학생에게 미리 물어볼 사전 질문 %d개를 한 번에 만드세요.
				학생은 %d개 질문을 한 화면에서 보고 원하는 순서로 답합니다. 따라서 각 질문은
				다른 질문의 답을 전제하지 않고 그 자체로 답할 수 있어야 합니다.

				규칙:
				- %d개 질문은 서로 다른 각도를 다루세요. 예: 구체적인 경험·사건, 그때의 판단과 행동,
				  부딪힌 어려움과 대응, 그 경험에서 얻은 배움과 변화, 앞으로의 계획과 이 장학금의 연결.
				- 학생이 사실과 감정을 구체적으로 떠올릴 수 있는 열린 질문으로 쓰세요.
				  예/아니오로 끝나는 질문은 금지합니다.
				- 각 질문은 한 문장, %d자 이내의 한국어로 작성하세요.
				- 평가·조언·요약·머리말·맺음말을 쓰지 마세요.

				출력 형식 (아래 형식만 출력하고 다른 텍스트는 넣지 마세요):
				1. 첫 번째 질문
				2. 두 번째 질문
				...
				%d. %d번째 질문
				""".formatted(
						nullSafe(scholarship.getTitle()),
						nullSafe(scholarship.getProvider()),
						nullSafe(question.getQuestionTitle()),
						nullSafe(question.getQuestionDescription()),
						QUESTION_COUNT,
						QUESTION_COUNT,
						QUESTION_COUNT,
						MAX_QUESTION_LENGTH,
						QUESTION_COUNT,
						QUESTION_COUNT);
	}

	private String nullSafe(String value) {
		return value == null ? "" : value;
	}
}
