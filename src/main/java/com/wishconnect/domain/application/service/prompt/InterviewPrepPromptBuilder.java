package com.wishconnect.domain.application.service.prompt;

import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 면접 예상 질문 생성 프롬프트 조립기.
 *
 * <p>{@link InterviewPromptBuilder}(사전 인터뷰)와 목적이 다르다. 저쪽은 자기소개서를 쓸 재료를
 * 모으려고 <b>AI 가 사용자에게</b> 묻는 질문을 만들고, 이쪽은 <b>면접관이 지원자에게</b> 물어볼
 * 법한 질문을 예측한다. 사용자 답변을 받지 않는다.
 *
 * <p>모델은 {@link LlmModel#INTERVIEW}(기본 Haiku)를 쓴다. 장학금 단위로 한 번만 만들어 캐시하므로
 * 호출량이 많지 않지만, 초안 생성만큼 문체 품질이 중요하지도 않다.
 */
@Component
public class InterviewPrepPromptBuilder {

	/** 장학금당 생성할 면접 예상 질문 개수. */
	public static final int QUESTION_COUNT = 6;

	/** 질문 한 줄의 최대 길이. 이보다 길면 화면에서 잘린다. */
	private static final int MAX_QUESTION_LENGTH = 70;

	/** 프롬프트에 넣을 자격조건 개수 상한. 전부 넣으면 토큰만 늘고 질문이 산만해진다. */
	private static final int MAX_CONDITIONS = 8;

	/** 자격조건 한 줄의 길이 상한. 공고 문장을 통째로 넣으면 컨텍스트가 금방 찬다. */
	private static final int MAX_CONDITION_LENGTH = 120;

	/** "1. 질문 | 의도" 형태의 한 줄. 의도는 없을 수도 있다. */
	private static final Pattern NUMBERED_LINE =
			Pattern.compile("^\\s*(\\d{1,2})\\s*[.)]\\s*(.+)$");

	private static final String KICKOFF_USER_MESSAGE = "면접 예상 질문을 만들어주세요.";

	/**
	 * 장학금 하나에 대한 면접 예상 질문 생성 요청을 조립한다.
	 *
	 * @param scholarship 대상 장학금
	 * @param conditions  이 장학금의 자격조건. 없으면 빈 리스트를 넘긴다
	 */
	public LlmChatRequest build(Scholarship scholarship, List<ScholarshipCondition> conditions) {
		return LlmChatRequest.of(LlmModel.INTERVIEW,
				buildSystemPrompt(scholarship, conditions),
				List.of(LlmMessage.user(KICKOFF_USER_MESSAGE)));
	}

	/**
	 * LLM 응답에서 질문과 의도를 뽑는다.
	 *
	 * <p>번호 목록을 우선 파싱하고, 모델이 형식을 어겨 번호가 없으면 비어 있지 않은 줄을 그대로
	 * 질문으로 쓴다. 의도({@code |} 뒤)는 없어도 된다 — 질문만으로도 쓸모가 있어 버리지 않는다.
	 *
	 * @return 추출된 질문 목록. 하나도 뽑지 못하면 빈 리스트
	 */
	public List<Generated> parse(String response) {
		if (response == null || response.isBlank()) {
			return List.of();
		}

		List<Generated> questions = new ArrayList<>();
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

	private void addIfNotBlank(List<Generated> target, String candidate) {
		String trimmed = candidate.trim();
		if (trimmed.isEmpty()) {
			return;
		}
		int separator = trimmed.indexOf('|');
		if (separator < 0) {
			target.add(new Generated(trimmed, null));
			return;
		}
		String question = trimmed.substring(0, separator).trim();
		String intent = trimmed.substring(separator + 1).trim();
		if (question.isEmpty()) {
			return;
		}
		target.add(new Generated(question, intent.isEmpty() ? null : intent));
	}

	private String buildSystemPrompt(Scholarship scholarship, List<ScholarshipCondition> conditions) {
		return """
				당신은 한국 대학 장학금 면접을 준비하는 학생을 돕는 코치입니다.

				<장학금>
				이름: %s
				주관: %s
				안내: %s
				선발 관련 공고 문장: %s
				지원 자격: %s
				</장학금>

				과제:
				이 장학금 면접에서 면접관이 물어볼 법한 질문 %d개를 만드세요.

				규칙:
				- 이 장학금의 성격과 지원 자격에 맞는 질문을 쓰세요. 어떤 장학금에나 통하는
				  일반적인 질문("자기소개 해주세요")은 최대 1개까지만 허용합니다.
				- %d개 질문은 서로 다른 각도를 다루세요. 예: 지원 동기와 이 장학금을 고른 이유,
				  학업·활동에서의 구체적 경험, 어려움을 겪고 대응한 사례, 자격 요건과 본인 상황의 연결,
				  수혜 후 계획과 기여.
				- 공고에 없는 사실을 지어내지 마세요. 자격 요건이 주어지지 않았다면 그것을 전제한
				  질문을 만들지 마세요.
				- 각 질문은 한 문장, %d자 이내의 한국어로 쓰세요.
				- 각 질문 뒤에 세로줄(|)을 두고, 면접관이 그 질문으로 무엇을 보려는지 한 문장으로
				  덧붙이세요. 학생이 준비 방향을 잡는 데 씁니다.
				- 평가·조언·머리말·맺음말을 쓰지 마세요.

				출력 형식 (아래 형식만 출력하고 다른 텍스트는 넣지 마세요):
				1. 첫 번째 질문 | 이 질문으로 보려는 것
				2. 두 번째 질문 | 이 질문으로 보려는 것
				...
				%d. %d번째 질문 | 이 질문으로 보려는 것
				""".formatted(
						nullSafe(scholarship.getTitle()),
						nullSafe(scholarship.getProvider()),
						summaryOf(scholarship),
						nullSafe(scholarship.getInterviewEvidence()),
						conditionsOf(conditions),
						QUESTION_COUNT,
						QUESTION_COUNT,
						MAX_QUESTION_LENGTH,
						QUESTION_COUNT,
						QUESTION_COUNT);
	}

	/** 요약이 없으면 설명 앞부분으로 대신한다. 둘 다 없으면 빈 문자열이다. */
	private String summaryOf(Scholarship scholarship) {
		if (scholarship.getSummary() != null && !scholarship.getSummary().isBlank()) {
			return scholarship.getSummary();
		}
		String description = scholarship.getDescription();
		if (description == null || description.isBlank()) {
			return "";
		}
		return description.length() > 500 ? description.substring(0, 500) : description;
	}

	private String conditionsOf(List<ScholarshipCondition> conditions) {
		if (conditions == null || conditions.isEmpty()) {
			// 자격 정보가 없다는 사실을 명시한다. 비워 두면 모델이 흔한 자격을 지어내기 쉽다.
			return "(공고에서 확인되지 않음)";
		}
		StringBuilder joined = new StringBuilder();
		int count = 0;
		for (ScholarshipCondition condition : conditions) {
			String value = condition.getValueString();
			if (value == null || value.isBlank()) {
				continue;
			}
			String cleaned = value.replaceAll("\\s+", " ").trim();
			if (cleaned.length() > MAX_CONDITION_LENGTH) {
				cleaned = cleaned.substring(0, MAX_CONDITION_LENGTH);
			}
			joined.append(joined.isEmpty() ? "" : " / ").append(cleaned);
			if (++count >= MAX_CONDITIONS) {
				break;
			}
		}
		return joined.isEmpty() ? "(공고에서 확인되지 않음)" : joined.toString();
	}

	private String nullSafe(String value) {
		return value == null ? "" : value;
	}

	/** LLM 이 만든 질문 한 건. 저장 전 단계라 엔티티와 분리해 둔다. */
	public record Generated(String questionText, String intent) {
	}
}
