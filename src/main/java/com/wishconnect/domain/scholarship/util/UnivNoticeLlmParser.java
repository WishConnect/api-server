package com.wishconnect.domain.scholarship.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.scholarship.dto.ParsedNotice;
import com.wishconnect.domain.scholarship.entity.ConditionNecessity;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.NoticeKind;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import com.wishconnect.domain.scholarship.entity.SubmissionChannel;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
	 * 파서 전용 응답 토큰 상한.
	 *
	 * <p>공용 기본값(4,096)으로는 조건 12개 + 각 조건의 원문 인용이 들어가면 잘린다.
	 * 잘린 응답은 재시도해도 같은 지점에서 잘리므로, 상한을 넉넉히 두는 편이 싸다.
	 */
	private static final int PARSER_MAX_TOKENS = 12_000;

	/**
	 * 프롬프트 개정 번호. 프롬프트를 고칠 때마다 올린다.
	 *
	 * <p>파싱 이력에 함께 저장한다. 이게 없으면 "프롬프트를 고쳤더니 좋아졌나"를 판단할 수 없다 —
	 * 결과만 쌓여 있고 무엇과 비교하는지 알 수 없기 때문이다.
	 */
	/**
	 * 프롬프트 판.
	 *
	 * <p><b>프롬프트를 고치면 반드시 함께 올린다.</b> 이 값으로 "이 건은 어떤 프롬프트로 뽑혔나" 를
	 * 되짚고, 재파싱 대상도 이걸로 고른다. 2026-08-18 에 이 규칙을 어겨 하루 동안 프롬프트를
	 * 다섯 번 고치는 내내 v2 로 뒀다 — 아침에 뽑힌 것과 저녁에 뽑힌 것이 같은 라벨을 달아
	 * 구분할 수 없게 됐다.
	 *
	 * <p>v3 은 새로 바꾼 게 아니라 <b>이미 바뀐 것에 이름을 붙인 것</b>이다. v2 대비:
	 * 제목 판별 기준과 제목 동봉 · 자소서/면접 판단 · 공지 종류(RECRUITMENT/RESULT/GUIDE) ·
	 * 통합 공고 · 제출 방식과 경로 · FINANCIAL_AID_TYPE 정의 축소.
	 */
	public static final String PROMPT_VERSION = "v3";


	/** 프롬프트에 나열한 조건 유형과 스키마 enum 이 어긋나지 않도록 한 곳에서 만든다. */
	private static final List<String> CONDITION_TYPE_NAMES = List.of(
			"INCOME_CRITERIA", "ACADEMIC_CRITERIA", "GRADE_LEVEL", "REGION_RESIDENCY",
			"MAJOR_FIELD", "SPECIFIC_QUALIFICATION", "RESTRICTION", "RECOMMENDATION_REQUIRED",
			"UNIVERSITY_TYPE", "FINANCIAL_AID_TYPE");

	/**
	 * 응답 형식을 강제하는 JSON Schema.
	 *
	 * <p>이걸 걸면 코드펜스·앞뒤 설명·타입 불일치가 <b>구조적으로 발생할 수 없다</b>.
	 * 아래 {@code readResponse} 의 방어 코드는 그래도 남겨 둔다 — 스키마는 형식만 보장하고
	 * 토큰 잘림과 거부는 여전히 막지 못하기 때문이다.
	 *
	 * <p>모든 필드를 nullable 로 두는 것이 이 파서의 원칙이라, 스키마에서도 각 타입에
	 * {@code null} 을 허용하고 {@code required} 로 전 필드를 요구한다. 구조화 출력은
	 * required 에 없는 필드를 아예 못 내보내므로, "값이 없으면 null" 을 표현하려면
	 * 이 조합이어야 한다.
	 */
	private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
			"type", "object",
			"additionalProperties", false,
			"required", List.of("title", "provider", "scholarshipType", "noticeKind", "combined",
					"submissionMethod", "submissionChannel", "essayRequirement",
					"essayEvidence", "interviewRequirement", "interviewEvidence", "applicationStart",
					"applicationEnd", "periodEvidence", "selectionCount", "amount", "summary",
					"documents", "conditions"),
			"properties", buildProperties());

	private static Map<String, Object> buildProperties() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("title", nullable("string"));
		properties.put("provider", nullable("string"));
		properties.put("scholarshipType", nullableEnum("INTERNAL", "EXTERNAL", "WORK_STUDY"));
		properties.put("applicationStart", nullable("string"));
		properties.put("applicationEnd", nullable("string"));
		properties.put("periodEvidence", nullable("string"));
		properties.put("selectionCount", nullable("integer"));
		properties.put("amount", nullable("integer"));
		properties.put("summary", nullable("string"));
		properties.put("noticeKind", nullableEnum("RECRUITMENT", "RESULT", "GUIDE"));
		properties.put("combined", Map.of("type", "boolean"));
		properties.put("submissionMethod", nullable("string"));
		properties.put("submissionChannel",
				nullableEnum("ONLINE", "EMAIL", "POST", "VISIT", "FAX", "MIXED"));
		properties.put("essayRequirement", nullableEnum("REQUIRED", "CONDITIONAL", "NOT_REQUIRED"));
		properties.put("essayEvidence", nullable("string"));
		properties.put("interviewRequirement", nullableEnum("REQUIRED", "CONDITIONAL", "NOT_REQUIRED"));
		properties.put("interviewEvidence", nullable("string"));
		properties.put("documents", Map.of("type", "array", "items", Map.of("type", "string")));
		properties.put("conditions", Map.of(
				"type", "array",
				"items", Map.of(
						"type", "object",
						"additionalProperties", false,
						"required", List.of("type", "evidence", "necessity", "refLabels",
								"operator", "valueInt", "valueIntMax"),
						"properties", conditionProperties())));
		return properties;
	}

	private static Map<String, Object> conditionProperties() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("type", Map.of("type", "string", "enum", CONDITION_TYPE_NAMES));
		properties.put("evidence", Map.of("type", "string"));
		properties.put("necessity", Map.of("type", "string", "enum", List.of("REQUIRED", "PREFERRED")));
		properties.put("refLabels", Map.of("type", "array", "items", Map.of("type", "string")));
		properties.put("operator", nullableEnum("GTE", "LTE", "BETWEEN", "EQ"));
		properties.put("valueInt", nullable("integer"));
		properties.put("valueIntMax", nullable("integer"));
		return properties;
	}

	private static Map<String, Object> nullable(String type) {
		return Map.of("type", List.of(type, "null"));
	}

	/**
	 * 값이 없을 수도 있는 열거 필드.
	 *
	 * <p><b>{@code type} 을 같이 쓰지 않는다.</b> {@code {"type":["string","null"],"enum":[...]}} 로
	 * 두면 API 가 400 으로 거부한다 — {@code Enum value 'INTERNAL' does not match declared type
	 * '['string','null']'}. 파서가 통째로 실패하던 원인이었다.
	 *
	 * <p>{@code enum} 만 두면 허용된 값 자체가 타입을 말해 주므로 {@code type} 이 필요 없다.
	 * ({@code type} 없는 {@code nullable()} 은 문제없다 — enum 과 결합할 때만 터진다)
	 */
	private static Map<String, Object> nullableEnum(String... values) {
		List<Object> allowed = new java.util.ArrayList<>(Arrays.asList(values));
		allowed.add(null);
		return Map.of("enum", Collections.unmodifiableList(allowed));
	}




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
			  "noticeKind": "RECRUITMENT"|"RESULT"|"GUIDE"|null,
			  "combined": true|false,
			  "submissionMethod": 문자열|null,
			  "submissionChannel": "ONLINE"|"EMAIL"|"POST"|"VISIT"|"FAX"|"MIXED"|null,
			  "essayRequirement": "REQUIRED"|"CONDITIONAL"|"NOT_REQUIRED"|null,
			  "essayEvidence": 문자열|null,
			  "interviewRequirement": "REQUIRED"|"CONDITIONAL"|"NOT_REQUIRED"|null,
			  "interviewEvidence": 문자열|null,
			  "documents": [문자열],
			  "conditions": [{"type": 문자열, "evidence": 문자열, "necessity": "REQUIRED"|"PREFERRED",
			                  "refLabels": [문자열], "operator": 문자열|null,
			                  "valueInt": 정수|null, "valueIntMax": 정수|null}]
			}

			절대 규칙:
			- 본문에 근거가 없는 값은 반드시 null 로 둔다. 추측·유추·계산으로 값을 만들지 마라.
			- applicationStart/applicationEnd 를 채웠다면 periodEvidence 에 그 근거가 된 본문 문장을
			  한 글자도 바꾸지 않고 그대로 인용한다. 인용할 문장이 없으면 기간을 null 로 둔다.
			  [제목] 에 기간이 적혀 있으면 그것도 근거로 인정한다 — "모집(6. 22. ~ 7. 24.)" 처럼
			  제목에만 날짜가 있는 공고가 있다.
			- 마감일만 있고 시작일이 없으면 applicationStart 만 null 로 둔다.
			- conditions 의 evidence 도 마찬가지로 본문 문장을 그대로 인용한다. 요약·재작성 금지.
			  인용할 문장이 없으면 그 조건을 아예 넣지 마라.

			판별 기준:
			- 신청기간은 '학생이 지원서를 내는 기간'이다. 다음은 신청기간이 아니다.
              근무기간, 근로기간, 장학금 지급기간, 서류 유효기간, 심사기간, 발표일, 게시기간.
			- scholarshipType: 근로·인턴 대가로 지급되면 WORK_STUDY, 외부 재단·기관이 주면 EXTERNAL,
			  대학이 자체 재원으로 주면 INTERNAL.
			- title: 장학금 이름이 드러나게. 입력의 [제목] 을 그대로 쓰되 게시판 말머리
			  ([교외], [교내], ★[필독], (재공고) 같은 분류·강조 표시)는 뺀다. [제목] 이 없으면
			  본문 첫머리에서 찾는다. 그래도 없을 때만 null 이다.
			- amount: 1인당 지급액(원). 사업 예산 총액이나 누적 지원 실적은 넣지 마라.
			- selectionCount: 선발 인원. 지원자 수·경쟁률은 넣지 마라.
			- summary: 한 문장(80자 이내). 본문 내용만으로 쓴다.
			- noticeKind: 이 공지가 무엇인가.
			  RECRUITMENT  장학생을 모집·선발하는 공고
			  RESULT       선발 결과·지급 일정 안내 (이미 끝난 전형이라 모집기간이 없는 게 정상)
			  GUIDE        장학금 자체가 아닌 안내 — 연락처 변경, 계좌 등록, 서류 제출 방법 안내
			- combined: 한 공고에 서로 다른 장학금이 여러 개 실려 있으면 true.
			  표로 장학금명·신청대상·금액·인원을 나열하는 "통합장학금" 공고가 그렇다.
			  하나의 장학금을 여러 문단으로 설명한 것은 false 다.
			- submissionMethod: 어떻게 내는가. 온라인 신청이면 그 시스템 이름을, 우편·방문 제출이면
			  그 사실과 도착 기준을 적는다. 예: "우편·방문 접수(7.30 오전 10:00 도착분에 한함)".
			  마감이 온라인 자정인지 방문 마감인지에 따라 준비가 달라지므로 중요하다.
			- submissionChannel: 위 방식을 하나로 분류한다.
			  ONLINE 웹 신청(학교 시스템·구글폼·네이버폼) / EMAIL 이메일 / POST 우편·등기
			  VISIT 직접 방문 / FAX 팩스 / MIXED 둘 이상을 함께 요구
			  "시스템에서 신청 후 서류는 방문 제출" 처럼 섞이면 MIXED 다.
			- documents: 제출서류 이름만. 부수(1부)·설명·괄호 주석은 제외한다.
			- essayRequirement / interviewRequirement: 아래 넷 중 하나다.
			  REQUIRED     모두가 내야 한다 — "자기소개서 제출", "면접전형 진행"
			  CONDITIONAL  일부만 — "서류 합격자에 한해 면접", "1차 통과자만 자기소개서 제출"
			  NOT_REQUIRED 공고가 없다고 밝힌 경우 — "면접 없이 서류로만 선발"
			  null         공고에 아무 언급이 없을 때. 없다고 단정하지 마라.
			  자기소개서는 이름이 달라도 같은 것으로 본다 — 학업계획서·수학계획서·지원동기서·에세이.
			- essayEvidence / interviewEvidence: 위 판단의 근거가 된 본문 문장을 그대로 인용한다.
			  값을 REQUIRED·CONDITIONAL·NOT_REQUIRED 로 정했으면 반드시 채운다. 인용할 문장이
			  없으면 판단을 null 로 되돌린다.

			conditions 의 type 은 아래 중 하나만 쓴다. 해당 없으면 그 조건을 넣지 마라.
			- INCOME_CRITERIA: 소득분위·학자금 지원구간·기초생활·차상위 등 경제 요건
			- ACADEMIC_CRITERIA: 평점·백분위·이수학점 등 성적 요건
			- GRADE_LEVEL: 학년·학기 요건 (예: 2학년 이상, 대학 3~7학기, 신입생)
			- REGION_RESIDENCY: 본인·보호자의 거주지 또는 출신지 요건
			- MAJOR_FIELD: 학과·전공·계열 요건
			- SPECIFIC_QUALIFICATION: 한부모·장애·다문화·국가유공·봉사·수상·자격증 등 특수 자격
			- RESTRICTION: 지원 제한 (중복수혜 불가, 휴학생 제외, 기수혜자 제외 등)
			- RECOMMENDATION_REQUIRED: 지도교수·학교장 추천 등 추천이 필요한 요건
			- UNIVERSITY_TYPE: 대학 구분 요건 (4년제·전문대·대학원·해외대학 등)
			- FINANCIAL_AID_TYPE: 이 장학금이 <무엇을 지원하는가> (등록금·생활비·해외연수·창업·취업 등).
			  <무엇을 해야 하는가>는 여기가 아니다. 아래는 자격 요건이므로 다른 유형으로 분류한다.
			    "국가장학금 신청자"      → RESTRICTION (신청 절차를 밟아야 지원 가능)
			    "등록금 완납자"          → RESTRICTION
			    "국가근로장학금 신청자"   → RESTRICTION
			  이 유형은 언제나 우대로 저장되므로, 필수 요건을 여기 넣으면 자격 없는 학생이 통과한다.

			necessity: 자격요건이면 REQUIRED, 우대사항·가산점이면 PREFERRED.
			- "우대", "가산점", "우선 선발", "~하면 유리" 는 PREFERRED 다.
			- FINANCIAL_AID_TYPE 은 자격이 아니라 지원 성격이므로 언제나 PREFERRED 다.
			- 판단이 서지 않으면 REQUIRED 로 둔다.

			refLabels: 조건이 가리키는 대상을 아래 목록의 표기 그대로 적는다. 목록에 없으면 빈 배열.
			- REGION_RESIDENCY: 본문에 쓰인 지역명 그대로 (예: "대구광역시 서구", "경기도").
			  "도내"·"관내"처럼 지역명이 없으면 빈 배열.
			- SPECIFIC_QUALIFICATION: 기초생활수급자 / 한부모 가정 / 국가유공자 / 차상위 계층 /
			  다문화 가정 / 장애인 가정 / 다자녀 가정 / 독립유공자 후손 /
			  공상 및 순직 군인/경찰/소방/공무원 가정 / 조손 가정 / 북한이탈주민 /
			  중소기업 제작자 / 장애인 / 자립준비청년 / 예체능 특기자
			- MAJOR_FIELD: 인문사회계열 / 공학계열 / 자연과학계열 / 예체능계열 / 의학계열 / 광역계열
			- FINANCIAL_AID_TYPE: 해외연수 / 교환학생, 등록금 지원, 생활비 지원, 대외활동 / 봉사활동,
			  예체능 / 특기 지원, 창업지원, 학업 / 연구 / 프로젝트, 취업 / 진로 지원
			- RESTRICTION: 휴학생을 제외한다면 "ON_LEAVE". 그 외에는 빈 배열.
			- 나머지 유형은 빈 배열.

			operator/valueInt/valueIntMax: 수치 기준이 있는 조건만 채운다. 없으면 전부 null.
			- INCOME_CRITERIA: 분위 숫자(1~10). "N분위 이하" -> LTE, valueInt N.
			- ACADEMIC_CRITERIA: 평점은 100배 정수(2.75 -> 275, 4.5 만점). "이상" -> GTE.
            - GRADE_LEVEL: 학기 단위. "대학N~M학기" -> BETWEEN. "N학년"은 학기로 2N-1~2N.
              "N학년 이상" -> GTE, valueInt 2N-1.
            - 숫자가 evidence 안에 실제로 있어야 한다. 없으면 null 로 둔다. 추측 금지.

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
	/**
	 * 파싱에 넣을 본문. 못 고르면 {@code empty} → 호출측이 SKIPPED 로 남긴다.
	 *
	 * <p>예전에는 페이지 전체 텍스트({@code document.text()})를 그대로 썼다. 소음 셀렉터로 메뉴를
	 * 걷어내긴 했지만 학교마다 마크업이 달라 다 걸리지 않았고, 결국 LLM 이 공지 대신 대학 소개문을
	 * 읽었다. 실측에서 100건 중 35건이 그 상태였다.
	 *
	 * <p>이제 본문 영역을 직접 고른다. 못 고르거나 골라낸 게 메뉴처럼 보이면 <b>LLM 을 부르지 않는다.</b>
	 * 근거 없는 값이 DB 에 남는 것보다 낫고, 크레딧도 아낀다.
	 */
	/** 본문이 이미지뿐이라 alt 로 대체했는가. OCR 대상을 고르는 데 쓴다. */
	public boolean isBodyFromImageAlt(String rawHtml) {
		if (rawHtml == null || rawHtml.isBlank()) {
			return false;
		}
		Document document = Jsoup.parse(rawHtml);
		document.select(NOISE_SELECTOR).remove();
		return NoticeHtmlExtractor.bodyFromImageAlt(document, null);
	}

	/** 본문 자리가 포스터 이미지뿐인가. {@link #extractBody} 가 비었을 때 사유를 가르는 데 쓴다. */
	public boolean isImageOnly(String rawHtml) {
		if (rawHtml == null || rawHtml.isBlank()) {
			return false;
		}
		Document document = Jsoup.parse(rawHtml);
		document.select(NOISE_SELECTOR).remove();
		return NoticeHtmlExtractor.imageOnly(document, null);
	}

	public Optional<ExtractedBody> extractBody(String rawHtml) {
		if (rawHtml == null || rawHtml.isBlank()) {
			return Optional.empty();
		}
		Document document = Jsoup.parse(rawHtml);
		document.select(NOISE_SELECTOR).remove();

		Optional<String> selected = NoticeHtmlExtractor.body(document, null);
		if (selected.isEmpty()) {
			return Optional.empty();
		}
		String text = selected.get();
		if (text.length() < MIN_BODY_CHARS || NoticeHtmlExtractor.looksLikeChrome(text)) {
			return Optional.empty();
		}
		if (text.length() <= MAX_BODY_CHARS) {
			return Optional.of(new ExtractedBody(text, false, text.length()));
		}
		return Optional.of(new ExtractedBody(text.substring(0, MAX_BODY_CHARS), true, text.length()));
	}

	/**
	 * LLM 에 넣을 본문과 그 가공 이력.
	 *
	 * <p>{@code truncated} 가 중요한 이유는 <b>잘린 채 성공한 경우</b>다. 앞 6,000자에 핵심이
	 * 있으면 파싱은 성공하지만 뒷부분의 제출서류·조건 일부를 조용히 잃는다. 실패했을 때만
	 * 기록하면 이 손실이 영영 드러나지 않는다.
	 *
	 * <p>잘림이 잦다면 상한을 올리기 전에 {@code NOISE_SELECTOR} 를 먼저 의심해야 한다.
	 * 메뉴·푸터가 안 걷힌 탓이라면 상한을 올려도 토큰만 더 쓰고 본문은 여전히 밀려난다.
	 *
	 * @param text           LLM 입력 텍스트 (상한 적용 후)
	 * @param truncated      상한을 넘어 잘렸는지
	 * @param originalLength 자르기 전 정제 본문 길이
	 */
	public record ExtractedBody(String text, boolean truncated, int originalLength) {
	}

	/** 파싱 요청을 조립한다. 모델은 PARSING 프로필(기본 Haiku)을 쓴다. */
	/**
	 * @param noticeTitle 게시판에서 뽑은 공고 제목. 없으면 null.
	 *
	 *     <p>제목을 따로 보내는 이유가 있다. 본문 영역만 정확히 잘라 보내게 되면서
	 *     <b>제목이 통째로 빠지는 게시판</b>이 생겼다(건국대). LLM 이 제목을 못 낸 게 아니라
	 *     볼 수가 없었던 것이다. 게다가 제목에 기간이 들어 있는 경우가 많다 —
	 *     "…무상기숙사 장학생 모집(6. 22. ~ 7. 24.)" 처럼. 안 보내면 그 정보도 함께 버린다.
	 */
	public LlmChatRequest buildRequest(String noticeTitle, String bodyText) {
		String message = (noticeTitle == null || noticeTitle.isBlank())
				? "[본문]\n" + bodyText
				: "[제목]\n" + noticeTitle + "\n\n[본문]\n" + bodyText;
		return LlmChatRequest.structured(LlmModel.PARSING, SYSTEM_PROMPT,
				List.of(LlmMessage.user(message)), PARSER_MAX_TOKENS, RESPONSE_SCHEMA);
	}

	/**
	 * 자소서·면접 판단을 근거와 함께 확정한다.
	 *
	 * <p>기간·조건과 같은 규칙을 적용한다 — <b>인용문이 본문에 실제로 없으면 판단을 버린다.</b>
	 * 여기서 지어낸 값은 사용자가 면접이 있는 줄 모르고 지원하거나, 자소서를 준비하지 않게 만든다.
	 *
	 * @return 검증을 통과한 값. 근거가 없거나 값이 이상하면 빈 결과(둘 다 null)
	 */
	public Requirement resolveRequirement(String level, String evidence, String bodyText,
			String noticeTitle) {
		if (level == null || level.isBlank()) {
			return Requirement.unknown();
		}
		RequirementLevel parsed;
		try {
			parsed = RequirementLevel.valueOf(level.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			return Requirement.unknown();
		}
		if (evidence == null || evidence.isBlank()
				|| !isEvidenceGrounded(evidence, bodyText, noticeTitle)) {
			log.debug("[UnivNoticeLlmParser] 근거 없는 전형 판단이라 버림. level={}", level);
			return Requirement.unknown();
		}
		return new Requirement(parsed, evidence.replaceAll("\\s+", " ").trim());
	}

	/** 제출 경로. 알 수 없는 값이 와도 터지지 않고 null 로 둔다. */
	public SubmissionChannel resolveSubmissionChannel(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return SubmissionChannel.valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** 공지 종류. 알 수 없는 값이 와도 터지지 않고 null 로 둔다. */
	public NoticeKind resolveNoticeKind(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return NoticeKind.valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** 자소서·면접 판단과 그 근거. */
	public record Requirement(RequirementLevel level, String evidence) {
		static Requirement unknown() {
			return new Requirement(null, null);
		}
	}

	/** 근거 문장이 본문이나 제목에 실제로 있는가. 제목까지 보는 건 제목에만 적힌 공고가 있어서다. */
	private boolean isEvidenceGrounded(String evidence, String bodyText, String noticeTitle) {
		String needle = normalizeForMatch(evidence);
		if (needle.length() < 6) {
			return false;
		}
		return normalizeForMatch(bodyText).contains(needle)
				|| (noticeTitle != null && normalizeForMatch(noticeTitle).contains(needle));
	}

	/** 게시판에서 공고 제목만 뽑는다. LLM 에 함께 넘기고, 폴백으로도 쓴다. */
	public Optional<String> extractTitle(String rawHtml) {
		if (rawHtml == null || rawHtml.isBlank()) {
			return Optional.empty();
		}
		return NoticeHtmlExtractor.title(Jsoup.parse(rawHtml), null);
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
		return resolvePeriod(notice, bodyText, LocalDate.now());
	}

	/**
	 * @param referenceDate 연도가 생략된 날짜의 연도를 정할 기준일. 보통 그 공지를 수집한 날이다
	 */
	public Optional<Period> resolvePeriod(ParsedNotice notice, String bodyText, LocalDate referenceDate) {
		LocalDate start = parseDate(notice.applicationStart());
		LocalDate end = parseDate(notice.applicationEnd());
		if (end == null && start == null) {
			return Optional.empty();
		}
		// 마감일 없이 시작일만 있는 것은 신청기간으로서 의미가 없다.
		if (end == null) {
			return Optional.empty();
		}
		if (!isPeriodEvidenceGrounded(notice.periodEvidence(), bodyText)) {
			log.warn("[UnivLlmParser] 기간 근거 미확인 → 기간 폐기. evidence={}", notice.periodEvidence());
			return Optional.empty();
		}
		if (isNonApplicationPeriod(notice.periodEvidence())) {
			log.warn("[UnivLlmParser] 신청기간이 아닌 라벨 → 기간 폐기. evidence={}", notice.periodEvidence());
			return Optional.empty();
		}
		// 인용문에 없는 월·일을 마감일로 삼았다면 다른 날짜를 집은 것이다.
		// 본문 "6. 29.(월) 18시까지" 를 보고 6/22 를 넣은 사례가 있었다.
		if (!isDayOfMonthQuoted(end, notice.periodEvidence())) {
			log.warn("[UnivLlmParser] 마감일이 근거에 없는 날짜 → 기간 폐기. end={} evidence={}",
					end, notice.periodEvidence());
			return Optional.empty();
		}
		// 연도는 모델이 정하게 두지 않는다. 제목의 "(~9/18" 을 보고 2024 년으로 읽은 적이 있다.
		// 인용문에 네 자리 연도가 없으면 수집 시점을 기준으로 서버가 붙인다.
		if (!hasExplicitYear(notice.periodEvidence())) {
			LocalDate corrected = withYearNear(end, referenceDate);
			if (!corrected.equals(end)) {
				log.info("[UnivLlmParser] 근거에 연도가 없어 수집 시점 기준으로 교정. {} → {}", end, corrected);
				if (start != null) {
					start = withYearNear(start, referenceDate);
				}
				end = corrected;
			}
		}
		// 근거에 날짜가 하나뿐이면 시작일의 근거가 없다. "서류제출기간 : 2026. 8. 2.(일) 까지" 를
		// 보고 시작일에도 같은 날짜를 넣은 사례가 있었다 — 화면에는 "8/2 ~ 8/2" 로 나와
		// 하루만 접수하는 것처럼 보인다. 실제로는 그 전부터 받았을 가능성이 높다.
		if (start != null && !looksLikeDateRange(notice.periodEvidence())) {
			start = null;
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
	/**
	 * 기간 인용문의 <b>날짜</b>가 본문에 있는지 본다.
	 *
	 * <p>조건과 달리 문장 전체를 대조하지 않는다. 실제로 이런 일이 있었다.
	 *
	 * <pre>
	 *   본문      "접수마감 : ~2026.07.30(목) 오전 10:00 도착분에 한함"
	 *   LLM 인용  "~2026.07.30(목) 오전 10:00까지"
	 * </pre>
	 *
	 * 뜻은 같은데 끝만 바꿔 써서 대조에 실패했고, 멀쩡한 마감일이 버려졌다. 조사·어미를 다듬는 건
	 * 모델이 자연스럽게 하는 일이라 이걸로 폐기하면 손실이 크다.
	 *
	 * <p>날짜는 지어내기 어렵고 틀리면 바로 드러난다. 그래서 <b>날짜만</b> 본문에 있으면 인정한다.
	 * 조건({@code evidence})은 문장 전체의 의미가 중요하므로 지금 규칙을 그대로 둔다.
	 */
	static boolean isPeriodEvidenceGrounded(String evidence, String bodyText) {
		if (evidence == null || evidence.isBlank() || bodyText == null) {
			return false;
		}
		if (isEvidenceGrounded(evidence, bodyText)) {
			return true;
		}
		String normalizedBody = normalizeForMatch(bodyText);
		java.util.regex.Matcher dates = EVIDENCE_DATE.matcher(evidence);
		boolean found = false;
		while (dates.find()) {
			found = true;
			if (!normalizedBody.contains(normalizeForMatch(dates.group()))) {
				return false;   // 인용에 있는 날짜가 본문에 없다 = 지어낸 것
			}
		}
		if (found) {
			return true;
		}
		// 연도 없는 표기도 근거로 인정한다. "(~8/13)", "~8. 19.(수)까지", "'26. 8. 12.(수)~9. 9.(수)"
		// 처럼 연도를 생략하거나 줄여 쓰는 공고가 많은데, 네 자리 연도만 찾다가 멀쩡한 마감일을
		// 여덟 건 버렸다. 연도는 어차피 수집 시점을 기준으로 서버가 붙인다.
		for (java.time.MonthDay monthDay : monthDaysIn(evidence)) {
			found = true;
			if (!containsMonthDay(normalizedBody, monthDay)) {
				return false;
			}
		}
		return found;
	}

	/**
	 * 본문에 그 월일이 있는가. 자리수를 맞춰 쓴 표기도 같이 본다.
	 *
	 * <p>구두점을 지운 뒤 비교하므로 "8/6" 은 "86" 이 되는데, 본문이 "08.06" 으로 적었다면
	 * "0806" 이라 그냥 비교하면 어긋난다.
	 */
	private static boolean containsMonthDay(String normalizedBody, java.time.MonthDay monthDay) {
		int month = monthDay.getMonthValue();
		int day = monthDay.getDayOfMonth();
		return normalizedBody.contains("" + month + day)
				|| normalizedBody.contains(String.format("%02d%02d", month, day))
				|| normalizedBody.contains(String.format("%d%02d", month, day))
				|| normalizedBody.contains(String.format("%02d%d", month, day));
	}

	/** 인용문에서 연도 없는 "월일"을 뽑는다. 날짜로 성립하는 것만 남긴다. */
	private static List<java.time.MonthDay> monthDaysIn(String evidence) {
		List<java.time.MonthDay> found = new ArrayList<>();
		java.util.regex.Matcher matcher =
				EVIDENCE_MONTH_DAY.matcher(SHORT_YEAR.matcher(evidence).replaceAll(" "));
		while (matcher.find()) {
			int month = Integer.parseInt(matcher.group(1));
			int day = Integer.parseInt(matcher.group(2));
			try {
				found.add(java.time.MonthDay.of(month, day));
			} catch (java.time.DateTimeException ignored) {
				// 날짜가 아니다("2.0 이상" 같은 평점 표기). 근거로 세지 않는다.
			}
		}
		return found;
	}

	/** 인용문에 네 자리 연도가 있는가. 없으면 연도를 모델이 아니라 서버가 정한다. */
	static boolean hasExplicitYear(String evidence) {
		return evidence != null && EVIDENCE_DATE.matcher(evidence).find();
	}

	/**
	 * 이 날짜의 월·일이 인용문에 실제로 적혀 있는가.
	 *
	 * <p>연도는 생략될 수 있어도 월·일은 반드시 인용문에 있어야 한다. 본문에 적힌 것과 다른 날을
	 * 마감일로 넣은 사례를 막는 관문이다.
	 */
	static boolean isDayOfMonthQuoted(LocalDate date, String evidence) {
		if (date == null || evidence == null) {
			return false;
		}
		return monthDaysIn(evidence).contains(java.time.MonthDay.from(date));
	}

	/**
	 * 연도가 빠진 날짜에 기준일과 가장 가까운 연도를 붙인다.
	 *
	 * <p>공고는 대개 수집 직후 마감된다. 기준일보다 6개월 이상 과거로 계산되면 다음 해로 넘긴다 —
	 * 12월에 올라온 "1. 15. 까지" 를 열한 달 전으로 읽지 않기 위해서다.
	 */
	static LocalDate withYearNear(LocalDate date, LocalDate referenceDate) {
		if (date == null || referenceDate == null) {
			return date;
		}
		LocalDate candidate = safeWithYear(date, referenceDate.getYear());
		if (candidate.isBefore(referenceDate.minusMonths(6))) {
			return safeWithYear(date, referenceDate.getYear() + 1);
		}
		return candidate;
	}

	/** 2월 29일이 평년으로 옮겨가는 경우를 피한다. */
	private static LocalDate safeWithYear(LocalDate date, int year) {
		int day = Math.min(date.getDayOfMonth(), LocalDate.of(year, date.getMonthValue(), 1)
				.lengthOfMonth());
		return LocalDate.of(year, date.getMonthValue(), day);
	}

	/**
	 * 인용문이 <b>기간</b>을 가리키는가, 아니면 <b>한 시점</b>만 가리키는가.
	 *
	 * <p>시작일의 근거가 있는지 보는 데 쓴다. "서류제출기간 : 2026. 8. 2.(일) 까지" 를 보고
	 * 시작일에도 같은 날짜를 넣은 사례가 있었다 — 화면에는 "8/2 ~ 8/2" 로 나와 하루만 접수하는
	 * 것처럼 보인다.
	 *
	 * <p>날짜 개수로만 세면 안 된다. 공고는 뒤 날짜의 연도를 생략하는 일이 흔하다 —
	 * {@code "2026. 8. 1. ~ 8. 14."}. 그래서 <b>물결·범위 표시가 두 값 사이에 있는지</b>도 본다.
	 */
	private static boolean looksLikeDateRange(String evidence) {
		if (evidence == null) {
			return false;
		}
		java.util.Set<String> found = new java.util.LinkedHashSet<>();
		java.util.regex.Matcher matcher = EVIDENCE_DATE.matcher(evidence);
		while (matcher.find()) {
			found.add(normalizeForMatch(matcher.group()));
		}
		if (found.size() >= 2) {
			return true;
		}
		// "2026. 8. 1. ~ 8. 14." 처럼 뒤쪽 연도가 생략된 범위.
		return RANGE_BETWEEN_NUMBERS.matcher(evidence).find();
	}

	/** 숫자 사이에 놓인 범위 표시. 연도가 생략된 "8. 1. ~ 8. 14." 를 잡는다. */
	private static final java.util.regex.Pattern RANGE_BETWEEN_NUMBERS =
			java.util.regex.Pattern.compile("\\d\\s*[.]?\\s*[~∼〜\\-–—]\\s*\\d");

	/** 인용문에서 날짜를 집는다. "2026.07.30", "2026-07-30", "2026년 7월 30일" */
	private static final java.util.regex.Pattern EVIDENCE_DATE = java.util.regex.Pattern.compile(
			"\\d{4}\\s*[.\\-년]\\s*\\d{1,2}\\s*[.\\-월]\\s*\\d{1,2}");

	/**
	 * 연도 없는 "월일". "8/6", "8. 19.", "9월 9일" 을 잡는다.
	 *
	 * <p>앞뒤로 숫자가 붙은 것은 뺀다 — "2026.08.06" 의 "26.08" 이나 "10,320" 같은 금액을
	 * 날짜로 읽으면 안 된다. 월·일 범위는 별도로 확인한다("2.0 이상" 같은 평점 표기 배제).
	 */
	private static final java.util.regex.Pattern EVIDENCE_MONTH_DAY = java.util.regex.Pattern.compile(
			"(?<![\\d,])(\\d{1,2})\\s*[./\\-월]\\s*(\\d{1,2})(?![\\d,])");

	/** 줄여 쓴 연도. "'26. 8. 12." 의 "'26." 을 먼저 걷어내야 뒤의 월일이 제대로 잡힌다. */
	private static final java.util.regex.Pattern SHORT_YEAR = java.util.regex.Pattern.compile(
			"['`‘’]\\d{2}\\s*[.년]");

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
				resolved.add(new ResolvedCondition(type, snippet,
						parseNecessity(condition.safeNecessity(), type),
						condition.safeRefLabels(),
						parseOperator(condition.operator()),
						condition.valueInt(), condition.valueIntMax()));
			}
		}
		return resolved;
	}


	/**
	 * 자격요건인지 우대사항인지 읽는다. 알 수 없는 값은 {@code REQUIRED} 로 둔다 —
	 * 우대를 자격으로 잘못 보면 추천이 좁아질 뿐이지만, 반대는 지원할 수 없는 장학금을 추천하게 된다.
	 *
	 * <p>{@code FINANCIAL_AID_TYPE} 은 지원 성격이지 자격이 아니라 LLM 답변과 무관하게 우대로 강제한다.
	 */
	private static ConditionNecessity parseNecessity(String value, ConditionType type) {
		if (type == ConditionType.FINANCIAL_AID_TYPE) {
			return ConditionNecessity.PREFERRED;
		}
		try {
			return ConditionNecessity.valueOf(value.toUpperCase());
		} catch (IllegalArgumentException e) {
			return ConditionNecessity.REQUIRED;
		}
	}

	/** 알 수 없는 연산자는 EQ 로 둔다(기존 저장 규약). */
	private static ConditionOperator parseOperator(String value) {
		if (value == null || value.isBlank()) {
			return ConditionOperator.EQ;
		}
		try {
			return ConditionOperator.valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			log.warn("[UnivLlmParser] 알 수 없는 연산자 '{}' → EQ 로 둠", value);
			return ConditionOperator.EQ;
		}
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

	/**
	 * 검증을 통과한 자격조건. {@code snippet} 은 본문 원문이라 그대로 valueString 이 된다.
	 *
	 * @param refLabels 마스터에서 찾을 라벨. ID 해석은 서버가 한다 — LLM 에게 검증할 수 없는
	 *                  숫자를 고르게 하지 않는다.
	 */
	public record ResolvedCondition(
			ConditionType type,
			String snippet,
			ConditionNecessity necessity,
			List<String> refLabels,
			ConditionOperator operator,
			Integer valueInt,
			Integer valueIntMax
	) {
	}
}
