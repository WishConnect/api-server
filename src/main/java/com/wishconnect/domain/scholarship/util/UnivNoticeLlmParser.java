package com.wishconnect.domain.scholarship.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.scholarship.dto.ParsedNotice;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

/**
 * 대학 장학공지 본문을 LLM 으로 구조화하는 파서.
 *
 * <p>네트워크에 직접 나가지 않는다. 본문 추출 → 프롬프트 조립 → 응답 파싱 → 검증까지만 담당하고,
 * 실제 호출은 서비스가 {@code LlmClient} 로 수행한다. 덕분에 이 클래스는 크레딧 없이 단위 테스트가 된다.
 *
 * <p>설계에서 가장 신경 쓴 것은 <b>환각 방어</b>다. 잘못된 마감일은 모집 중인 공고를 마감 처리해
 * 노출에서 사라지게 만든다. "못 뽑는 것"보다 "틀리게 뽑는 것"이 훨씬 해로우므로,
 * 확신이 없으면 null 을 남기는 쪽으로 일관되게 기울였다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnivNoticeLlmParser {

	/**
	 * LLM 에 넘길 본문 최대 길이.
	 *
	 * <p>실측(수집 107건)에서 공고 본문은 평균 834자, 최대 2,000자 수준이다. 6,000자면 충분히 덮으면서
	 * 페이지 전체가 넘어가는 사고(한양대 raw_html 평균 346KB)를 막는 상한이 된다.
	 */
	private static final int MAX_BODY_CHARS = 6_000;

	/** 본문이 이보다 짧으면 파싱할 내용이 없다고 보고 호출 자체를 생략한다(토큰 낭비 방지). */
	private static final int MIN_BODY_CHARS = 50;

	/** 신청기간으로 인정할 최대 일수. 이보다 길면 근무기간·게시기간을 잘못 집은 것으로 본다. */
	private static final int MAX_PERIOD_DAYS = 300;

	/** 장학 금액 상한(원). 이보다 크면 예산 총액이나 누적 지원액을 집은 것이다. */
	private static final long MAX_AMOUNT = 100_000_000L;

	/** 선발 인원 상한. 이보다 크면 지원자 수나 무관한 숫자다. */
	private static final int MAX_SELECTION_COUNT = 10_000;

	/** 저장할 조건 최대 개수. 정규식 추출기(NoticeConditionExtractor)와 같은 상한을 쓴다. */
	private static final int MAX_CONDITIONS = 12;

	/** 조건 원문 최대 길이. 길면 조건과 무관한 내용이 섞여 들어온다. */
	private static final int MAX_SNIPPET_CHARS = 300;

	/**
	 * 신청기간이 아닌 기간을 가리키는 라벨.
	 *
	 * <p>기간 검증의 마지막 관문이다. 앞의 두 관문으로는 이 오류를 잡을 수 없다.
	 * 근거 인용 검증은 통과한다(LLM 이 본문에 실제로 있는 문장을 인용했으므로) 그리고
	 * 일수 상한도 통과한다(근무기간 6개월 = 180일 &lt; 300일). 즉 분류만 틀린 상태라
	 * 인용문에 무슨 라벨이 붙어 있는지를 직접 봐야 걸러진다.
	 *
	 * <p>실측 오탐: 경희대 "근무기간 : 2026.09.01 ~ 2027.02.28" 을 신청기간으로 집었다.
	 */
	private static final java.util.regex.Pattern NON_APPLICATION_LABEL = java.util.regex.Pattern.compile(
			"(근무\\s*기간|근로\\s*기간|지급\\s*기간|장학\\s*기간|게시\\s*기간|공지\\s*기간|노출\\s*기간"
					+ "|심사\\s*기간|선발\\s*기간|평가\\s*기간|발표\\s*기간|수혜\\s*기간|이수\\s*기간"
					+ "|활동\\s*기간|봉사\\s*기간|유효\\s*기간|발급\\s*기간|검증\\s*기간)");

	/** 신청기간을 가리키는 라벨. 위 라벨과 함께 등장하면 이쪽을 신뢰한다. */
	private static final java.util.regex.Pattern APPLICATION_LABEL = java.util.regex.Pattern.compile(
			"(신청\\s*기간|접수\\s*기간|모집\\s*기간|지원\\s*기간|지원\\s*일정|신청\\s*일정|접수\\s*일정"
					+ "|공모\\s*기간|신청\\s*기한|접수\\s*기한|제출\\s*기한|제출\\s*기간)");

	/** 본문에서 제거할 공통 UI 영역. 남겨두면 메뉴·푸터가 토큰을 잡아먹는다. */
	private static final String NOISE_SELECTOR =
			"script, style, noscript, nav, header, footer, aside, form, "
					+ ".gnb, .lnb, .snb, #gnb, #lnb, #header, #footer, .menu, .skip, .breadcrumb";

	private static final String SYSTEM_PROMPT = """
			너는 한국 대학의 장학금 공고 본문에서 정보를 추출하는 도구다.
			반드시 JSON 객체 하나만 출력한다(설명·코드펜스 금지).

			출력 형식:
			{
			  "title": 문자열|null,
			  "provider": 문자열|null,
			  "scholarshipType": "INTERNAL"|"EXTERNAL"|"WORK_STUDY"|null,
			  "applicationStart": "yyyy-MM-dd"|null,
			  "applicationEnd": "yyyy-MM-dd"|null,
			  "periodEvidence": 문자열|null,
			  "selectionCount": 정수|null,
			  "amount": 정수|null,
			  "summary": 문자열|null,
			  "documents": [문자열],
			  "conditions": [{"type": 문자열, "evidence": 문자열}]
			}

			절대 규칙:
			- 본문에 근거가 없는 값은 반드시 null 로 둔다. 추측·유추·계산으로 값을 만들지 마라.
			- applicationStart/applicationEnd 를 채웠다면 periodEvidence 에 그 근거가 된 본문 문장을
			  한 글자도 바꾸지 않고 그대로 인용한다. 인용할 문장이 없으면 기간을 null 로 둔다.
			- 마감일만 있고 시작일이 없으면 applicationStart 만 null 로 둔다.
			- conditions 의 evidence 도 마찬가지로 본문 문장을 그대로 인용한다. 요약·재작성 금지.
			  인용할 문장이 없으면 그 조건을 아예 넣지 마라.

			판별 기준:
			- 신청기간은 '학생이 지원서를 내는 기간'이다. 다음은 신청기간이 아니다.
              근무기간, 근로기간, 장학금 지급기간, 서류 유효기간, 심사기간, 발표일, 게시기간.
			- scholarshipType: 근로·인턴 대가로 지급되면 WORK_STUDY, 외부 재단·기관이 주면 EXTERNAL,
			  대학이 자체 재원으로 주면 INTERNAL.
			- provider: EXTERNAL 이면 재단·기관명, 그 외에는 대학명.
			- amount: 1인당 지급액(원). 사업 예산 총액이나 누적 지원 실적은 넣지 마라.
			- selectionCount: 선발 인원. 지원자 수·경쟁률은 넣지 마라.
			- summary: 한 문장(80자 이내). 본문 내용만으로 쓴다.
			- documents: 제출서류 이름만. 부수(1부)·설명·괄호 주석은 제외한다.

			conditions 의 type 은 아래 중 하나만 쓴다. 해당 없으면 그 조건을 넣지 마라.
			- INCOME_CRITERIA: 소득분위·학자금 지원구간·기초생활·차상위 등 경제 요건
			- ACADEMIC_CRITERIA: 평점·백분위·이수학점 등 성적 요건
			- GRADE_LEVEL: 학년·학기 요건 (예: 2학년 이상, 대학 3~7학기, 신입생)
			- REGION_RESIDENCY: 본인·보호자의 거주지 또는 출신지 요건
			- MAJOR_FIELD: 학과·전공·계열 요건
			- SPECIFIC_QUALIFICATION: 한부모·장애·다문화·국가유공·봉사·수상·자격증 등 특수 자격
			- RESTRICTION: 지원 제한 (중복수혜 불가, 휴학생 제외, 기수혜자 제외 등)
			- RECOMMENDATION_REQUIRED: 지도교수·학교장 추천 등 추천이 필요한 요건

			조건 규칙:
			- '지원 자격'에 해당하는 것만 넣는다. 제출서류·문의처·지급방법·선발일정은 조건이 아니다.
			- 한 문장에 조건이 둘이면(예: "3학년 이상, 평점 3.0 이상") 유형별로 나눠 각각 넣는다.
			  이때 evidence 는 각 조건이 포함된 본문 문장을 인용하면 된다.
			- 같은 조건을 여러 번 넣지 마라. 최대 12개.
			- 자격 요건이 본문에 없으면 conditions 를 빈 배열로 둔다. 억지로 만들지 마라.
			""";

	private final ObjectMapper objectMapper;

	/**
	 * {@code raw_html} 에서 LLM 입력용 본문 텍스트를 뽑는다.
	 *
	 * <p>수집기가 페이지 전체를 저장한 경우(한양대·경희대·성균관대) 메뉴·푸터가 본문의 수십 배라,
	 * 그대로 넘기면 토큰의 대부분이 버려진다. 공통 UI 영역을 걷어내고 텍스트만 남긴 뒤 상한으로 자른다.
	 *
	 * @return 본문 텍스트. 파싱할 내용이 없으면 {@code Optional.empty()}
	 */
	public Optional<String> extractBody(String rawHtml) {
		if (rawHtml == null || rawHtml.isBlank()) {
			return Optional.empty();
		}
		Document document = Jsoup.parse(rawHtml);
		document.select(NOISE_SELECTOR).remove();

		String text = document.text().replaceAll("\\s+", " ").trim();
		if (text.length() < MIN_BODY_CHARS) {
			return Optional.empty();
		}
		return Optional.of(text.length() > MAX_BODY_CHARS ? text.substring(0, MAX_BODY_CHARS) : text);
	}

	/** 파싱 요청을 조립한다. 모델은 PARSING 프로필(기본 Haiku)을 쓴다. */
	public LlmChatRequest buildRequest(String bodyText) {
		return LlmChatRequest.of(LlmModel.PARSING, SYSTEM_PROMPT, List.of(LlmMessage.user(bodyText)));
	}

	/**
	 * LLM 응답을 {@link ParsedNotice} 로 읽는다.
	 * 코드펜스로 감싸 오는 경우가 있어 벗겨낸 뒤 파싱하고, 실패하면 비운다.
	 */
	public Optional<ParsedNotice> readResponse(String rawResponse) {
		if (rawResponse == null || rawResponse.isBlank()) {
			return Optional.empty();
		}
		try {
			String json = rawResponse.strip();
			if (json.startsWith("```")) {
				json = json.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").strip();
			}
			// 앞뒤에 잡담이 붙어 오는 경우 대비: 첫 { 부터 마지막 } 까지만 취한다.
			int start = json.indexOf('{');
			int end = json.lastIndexOf('}');
			if (start < 0 || end <= start) {
				return Optional.empty();
			}
			return Optional.ofNullable(objectMapper.readValue(json.substring(start, end + 1), ParsedNotice.class));
		} catch (Exception e) {
			log.warn("[UnivLlmParser] 응답 파싱 실패: {}", e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * 신청기간을 검증해 받아들일지 결정한다.
	 *
	 * <p>네 가지 관문을 통과해야 인정한다. 각 관문이 잡는 오류가 다르므로 하나도 뺄 수 없다.
	 * <ol>
	 *   <li>날짜로 성립하는가</li>
	 *   <li>{@code periodEvidence} 인용문이 본문에 실제로 있는가 (지어낸 값 차단)</li>
	 *   <li>인용문의 라벨이 신청기간인가 (근무기간·게시기간 오인 차단)</li>
	 *   <li>기간이 {@value #MAX_PERIOD_DAYS}일 이내인가 (표가 뭉개져 무관한 두 날짜가 짝지어진 경우 차단)</li>
	 * </ol>
	 *
	 * @param bodyText LLM 에 넘긴 본문. 인용문 대조에 쓴다
	 */
	public Optional<Period> resolvePeriod(ParsedNotice notice, String bodyText) {
		LocalDate start = parseDate(notice.applicationStart());
		LocalDate end = parseDate(notice.applicationEnd());
		if (end == null && start == null) {
			return Optional.empty();
		}
		// 마감일 없이 시작일만 있는 것은 신청기간으로서 의미가 없다.
		if (end == null) {
			return Optional.empty();
		}
		if (!isEvidenceGrounded(notice.periodEvidence(), bodyText)) {
			log.warn("[UnivLlmParser] 기간 근거 미확인 → 기간 폐기. evidence={}", notice.periodEvidence());
			return Optional.empty();
		}
		if (isNonApplicationPeriod(notice.periodEvidence())) {
			log.warn("[UnivLlmParser] 신청기간이 아닌 라벨 → 기간 폐기. evidence={}", notice.periodEvidence());
			return Optional.empty();
		}
		if (start != null) {
			if (end.isBefore(start)) {
				return Optional.empty();
			}
			if (ChronoUnit.DAYS.between(start, end) > MAX_PERIOD_DAYS) {
				log.warn("[UnivLlmParser] 기간이 {}일 초과 → 폐기 ({} ~ {})",
						MAX_PERIOD_DAYS, start, end);
				return Optional.empty();
			}
		}
		return Optional.of(new Period(
				start == null ? null : start.atStartOfDay(),
				end.atTime(LocalTime.of(23, 59, 59))));
	}

	/**
	 * 인용문이 본문에 실제로 존재하는지 확인한다.
	 *
	 * <p>공백·구두점 차이는 허용한다. LLM 이 인용할 때 공백을 정규화하는 일이 흔한데,
	 * 그것까지 불일치로 보면 정상 추출도 버려지기 때문이다. 반대로 인용문 자체가 없으면
	 * 근거 없이 지어낸 것으로 간주한다.
	 */
	static boolean isEvidenceGrounded(String evidence, String bodyText) {
		if (evidence == null || evidence.isBlank() || bodyText == null) {
			return false;
		}
		String needle = normalizeForMatch(evidence);
		// 너무 짧은 인용은 우연히 일치할 수 있어 근거로 인정하지 않는다.
		if (needle.length() < 6) {
			return false;
		}
		return normalizeForMatch(bodyText).contains(needle);
	}

	private static String normalizeForMatch(String value) {
		return value.replaceAll("[\\s\\p{Punct}·~∼〜]", "");
	}

	/**
	 * 인용문이 신청기간이 아닌 기간을 가리키는지 판정한다.
	 *
	 * <p>두 라벨이 함께 있으면(예: "신청기간 이후 근무기간은…") 신청기간 쪽을 신뢰한다.
	 * 라벨이 아무것도 없으면 판단 근거가 없으므로 막지 않는다 — 여기서 과하게 막으면
	 * 라벨 없이 날짜만 적힌 정상 공고가 전부 버려진다.
	 */
	static boolean isNonApplicationPeriod(String evidence) {
		if (evidence == null || evidence.isBlank()) {
			return false;
		}
		if (APPLICATION_LABEL.matcher(evidence).find()) {
			return false;
		}
		return NON_APPLICATION_LABEL.matcher(evidence).find();
	}

	/**
	 * 자격조건을 검증해 저장할 것만 남긴다.
	 *
	 * <p>기간과 같은 근거 인용 방식을 쓴다. 잘못된 조건은 기간 오류만큼 해롭다 —
	 * 없는 조건을 만들어내면 자격 있는 학생이 추천에서 조용히 탈락하고, 화면에는 근거 없는
	 * 자격 문구가 노출된다. 그래서 <b>본문에 없는 인용문은 통째로 버린다.</b>
	 *
	 * <p>거르는 것: 알 수 없는 유형, 본문에 없는 인용(환각), 너무 짧아 근거가 못 되는 인용,
	 * 유형·문장이 같은 중복, {@value #MAX_CONDITIONS} 개 초과분.
	 *
	 * @param bodyText LLM 에 넘긴 본문. 인용문 대조에 쓴다
	 */
	public List<ResolvedCondition> resolveConditions(ParsedNotice notice, String bodyText) {
		List<ResolvedCondition> resolved = new ArrayList<>();
		LinkedHashSet<String> seen = new LinkedHashSet<>();

		for (ParsedNotice.Condition condition : notice.safeConditions()) {
			if (resolved.size() >= MAX_CONDITIONS) {
				break;
			}
			ConditionType type = parseConditionType(condition.type());
			if (type == null) {
				continue;
			}
			String evidence = condition.evidence() == null ? "" : condition.evidence().replaceAll("\\s+", " ").trim();
			if (!isEvidenceGrounded(evidence, bodyText)) {
				log.warn("[UnivLlmParser] 조건 근거 미확인 → 폐기. type={} evidence={}", type, evidence);
				continue;
			}
			String snippet = evidence.length() > MAX_SNIPPET_CHARS
					? evidence.substring(0, MAX_SNIPPET_CHARS)
					: evidence;
			if (seen.add(type + "|" + snippet)) {
				resolved.add(new ResolvedCondition(type, snippet));
			}
		}
		return resolved;
	}

	/** 프롬프트에 없는 값이나 오타가 오면 조건을 만들지 않는다(틀린 조건보다 없는 편이 낫다). */
	private static ConditionType parseConditionType(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return ConditionType.valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			log.warn("[UnivLlmParser] 알 수 없는 조건 유형 '{}' → 조건 폐기", value);
			return null;
		}
	}

	/** 금액·인원처럼 범위를 벗어나면 오추출이 분명한 값을 걸러낸다. */
	public Long resolveAmount(Long amount) {
		return amount != null && amount > 0 && amount <= MAX_AMOUNT ? amount : null;
	}

	public Integer resolveSelectionCount(Integer count) {
		return count != null && count > 0 && count <= MAX_SELECTION_COUNT ? count : null;
	}

	/** 알 수 없는 값이 오면 INTERNAL 로 둔다(교내 공지가 기본이므로 가장 덜 틀린 선택). */
	public ScholarshipType resolveType(String value) {
		if (value == null) {
			return ScholarshipType.INTERNAL;
		}
		try {
			return ScholarshipType.valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			log.warn("[UnivLlmParser] 알 수 없는 장학 유형 '{}' → INTERNAL", value);
			return ScholarshipType.INTERNAL;
		}
	}

	private static LocalDate parseDate(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(value.trim());
		} catch (Exception e) {
			return null;
		}
	}

	public record Period(LocalDateTime start, LocalDateTime end) {
	}

	/** 검증을 통과한 자격조건. {@code snippet} 은 본문 원문이라 그대로 valueString 이 된다. */
	public record ResolvedCondition(ConditionType type, String snippet) {
	}
}
