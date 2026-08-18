package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.application.config.LlmProperties;
import com.wishconnect.domain.scholarship.entity.NoticeParseLog;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.util.UnivNoticeLlmParser;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import org.mockito.ArgumentCaptor;
import com.wishconnect.domain.common.service.RegionResolver;
import com.wishconnect.domain.scholarship.repository.NoticeParseLogRepository;
import com.wishconnect.domain.user.repository.FamilyTypeRepository;
import com.wishconnect.domain.user.repository.InterestRepository;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.entity.ConditionNecessity;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.util.UnivNoticeLlmParser;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

/**
 * LLM 파싱 배치의 흐름 검증.
 *
 * <p>LlmClient 를 목으로 두므로 크레딧 없이 돌아간다. 파서 자체의 검증 규칙은
 * {@code UnivNoticeLlmParserTest} 에서 다루고, 여기서는 배치의 상태 전이와
 * 재파싱 덮어쓰기·dryRun 동작을 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UnivNoticeLlmParsingServiceTest {

	private static final String BODY = """
			2026학년도 2학기 운연장학 장학생을 모집합니다.
			신청기간 : 2026. 8. 1. ~ 2026. 8. 14.
			제출서류 : 자기소개서, 성적증명서
			선발인원 : 10명, 장학금액 : 1,000,000원
			""";

	private static final String LLM_RESPONSE = """
			{"title":"2026학년도 2학기 운연장학 신청 안내","provider":"경희대학교",
			 "scholarshipType":"INTERNAL","applicationStart":"2026-08-01","applicationEnd":"2026-08-14",
			 "periodEvidence":"신청기간 : 2026. 8. 1. ~ 2026. 8. 14.",
			 "selectionCount":10,"amount":1000000,"summary":"교내 운연장학 모집",
			 "documents":["자기소개서","성적증명서"]}
			""";

	@Mock private RawScholarshipRepository rawScholarshipRepository;
	@Mock private ScholarshipRepository scholarshipRepository;
	@Mock private ScholarshipConditionRepository scholarshipConditionRepository;
	@Mock private ScholarshipDocumentRepository scholarshipDocumentRepository;
	@Mock private LlmClient llmClient;
	@Mock private NoticeParseLogRepository noticeParseLogRepository;
	/** 포스터 저장. 파싱 성패와 무관하므로 스텁하지 않는다. */
	@Mock private ImageStorageService imageStorageService;
	@Mock private RegionResolver regionResolver;
	@Mock private FamilyTypeRepository familyTypeRepository;
	@Mock private InterestRepository interestRepository;

	private UnivNoticeLlmParsingService service;

	@BeforeEach
	void setUp() {
		service = new UnivNoticeLlmParsingService(
				rawScholarshipRepository, imageStorageService, scholarshipRepository,
				scholarshipConditionRepository, scholarshipDocumentRepository,
				new UnivNoticeLlmParser(new ObjectMapper()), llmClient,
				noticeParseLogRepository,
				new LlmProperties("claude-haiku-4-5", "claude-sonnet-5",
						"claude-haiku-4-5", "claude-haiku-4-5", 4096),
				new ObjectMapper(),
				new ConditionRefResolver(regionResolver, familyTypeRepository, interestRepository));
	}

	// --- Fixture ---

	private RawScholarship raw(Long id, String html, Scholarship linked, ParseStatus status) {
		RawScholarship raw = RawScholarship.builder()
				.source("UNIV_KHU")
				.sourceId("322765")
				.sourceUrl("https://www.khu.ac.kr/...")
				.rawHtml(html)
				.parseStatus(status)
				.build();
		setField(raw, "id", id);
		if (linked != null) {
			raw.markParsed(linked);
		}
		return raw;
	}

	private Scholarship existingScholarship() {
		Scholarship scholarship = Scholarship.builder()
				.title("잘못 파싱된 제목")
				.provider("UNIV_KHU")
				.scholarshipType(ScholarshipType.WORK_STUDY)
				// 정규식이 근무기간을 신청기간으로 잘못 넣어둔 상태
				.applicationStartAt(java.time.LocalDateTime.of(2026, 9, 1, 0, 0))
				.applicationEndAt(java.time.LocalDateTime.of(2027, 2, 28, 23, 59, 59))
				.primarySource("UNIV_KHU")
				.dedupKey("old-key")
				.build();
		setField(scholarship, "id", 500L);
		return scholarship;
	}

	private String html(String body) {
		return "<html><body><nav>메뉴</nav><div class='artclView'>" + body + "</div></body></html>";
	}

	private void givenPendingTargets(List<RawScholarship> targets) {
		given(rawScholarshipRepository.findBySourceStartingWithAndParseStatusOrderByIdAsc(
				eq("UNIV_"), eq(ParseStatus.PENDING), any(Pageable.class))).willReturn(targets);
	}

	private void givenAllTargets(List<RawScholarship> targets) {
		given(rawScholarshipRepository.findReparseTargets(
				eq("UNIV_"), eq(UnivNoticeLlmParser.PROMPT_VERSION), any(Pageable.class)))
				.willReturn(targets);
	}

	// --- 신규 파싱 ---

	@Test
	@DisplayName("PENDING 공고를 파싱해 장학금을 새로 만들고 PARSED 로 바꾼다")
	void parsesPendingNotice() {
		RawScholarship target = raw(1L, html(BODY), null, ParseStatus.PENDING);
		givenPendingTargets(List.of(target));
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);
		given(scholarshipRepository.findByDedupKey(any())).willReturn(Optional.empty());
		given(scholarshipRepository.save(any(Scholarship.class)))
				.willAnswer(invocation -> invocation.getArgument(0));

		var result = service.parse(20, false, false);

		assertThat(result.targetCount()).isEqualTo(1);
		assertThat(result.parsedCount()).isEqualTo(1);
		assertThat(target.getParseStatus()).isEqualTo(ParseStatus.PARSED);
		assertThat(result.items().get(0).afterPeriod()).isEqualTo("2026-08-01 ~ 2026-08-14");
		verify(scholarshipDocumentRepository).saveAll(anyList());
	}

	@Test
	@DisplayName("공공데이터는 대상에서 제외한다 — UNIV_ 접두사만 조회한다")
	void queriesOnlyUnivSources() {
		givenPendingTargets(List.of());

		service.parse(20, false, false);

		verify(rawScholarshipRepository).findBySourceStartingWithAndParseStatusOrderByIdAsc(
				eq("UNIV_"), eq(ParseStatus.PENDING), any(Pageable.class));
	}

	// --- 재파싱 덮어쓰기 ---

	@Test
	@DisplayName("재파싱은 기존 장학금을 덮어쓴다 — 잘못된 기간이 정정된다")
	void reparseOverwritesExisting() {
		Scholarship existing = existingScholarship();
		RawScholarship target = raw(2L, html(BODY), existing, ParseStatus.PARSED);
		givenAllTargets(List.of(target));
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);

		var result = service.parse(20, true, false);

		assertThat(result.parsedCount()).isEqualTo(1);
		// 근무기간(2026-09-01~2027-02-28) → 신청기간(2026-08-01~2026-08-14) 으로 정정
		assertThat(existing.getApplicationStartAt().toLocalDate())
				.isEqualTo(java.time.LocalDate.of(2026, 8, 1));
		assertThat(existing.getApplicationEndAt().toLocalDate())
				.isEqualTo(java.time.LocalDate.of(2026, 8, 14));
		assertThat(existing.getTitle()).isEqualTo("2026학년도 2학기 운연장학 신청 안내");
		assertThat(existing.getProvider()).isEqualTo("경희대학교");
		assertThat(existing.getScholarshipType()).isEqualTo(ScholarshipType.INTERNAL);
		// 새 행을 만들지 않는다 (dedupKey 가 바뀌어 중복 생성되는 것을 막아야 한다)
		verify(scholarshipRepository, never()).save(any(Scholarship.class));
		assertThat(result.items().get(0).beforePeriod()).isEqualTo("2026-09-01 ~ 2027-02-28");
		assertThat(result.items().get(0).afterPeriod()).isEqualTo("2026-08-01 ~ 2026-08-14");
	}

	/*
	운영에서 장학금 한 행을 공지 9건이 함께 가리키는 것을 발견했다. 정규식 파서가 제목을 못 뽑아
	페이지의 공유 버튼 문구를 제목으로 넣는 바람에 서로 다른 공지가 같은 dedupKey 로 묶인 것이다.
	그 상태로 재파싱하면 같은 행을 순서대로 덮어써 마지막 공지만 남는다 — 실제로 앞서 뽑아낸
	모집기간이 뒤 공지의 빈 값에 지워졌다.
	 */
	@Test
	@DisplayName("여러 공지가 한 장학금을 공유 중이면 덮어쓰지 않고 자기 행을 만든다")
	void splitsScholarshipSharedByMultipleNotices() {
		Scholarship shared = existingScholarship();
		RawScholarship target = raw(2L, html(BODY), shared, ParseStatus.PARSED);
		givenAllTargets(List.of(target));
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);
		// 이 장학금을 다른 공지도 가리키고 있다.
		given(rawScholarshipRepository.countByScholarship(shared)).willReturn(9L);
		given(scholarshipRepository.findByDedupKey(any())).willReturn(java.util.Optional.empty());
		given(scholarshipRepository.save(any(Scholarship.class)))
				.willAnswer(invocation -> invocation.getArgument(0));

		var result = service.parse(20, true, false);

		assertThat(result.parsedCount()).isEqualTo(1);
		// 공유 중인 행은 건드리지 않는다 — 다른 공지 8건의 결과가 여기 들어 있다.
		assertThat(shared.getTitle()).isNotEqualTo("2026학년도 2학기 운연장학 신청 안내");
		assertThat(shared.getApplicationStartAt().toLocalDate())
				.isEqualTo(java.time.LocalDate.of(2026, 9, 1));
		// 대신 자기 행을 새로 만든다.
		verify(scholarshipRepository).save(any(Scholarship.class));
	}

	/*
	조건을 정규식 추출기에서 LLM 으로 넘긴 뒤의 핵심 동작.
	정규식은 "가계 곤란으로 학업 유지가 어려운 자" 같은 서술형 요건을 아예 못 잡았다.
	 */
	@Test
	@DisplayName("LLM 이 뽑은 조건을 유형·원문·수치·필수여부까지 한 번에 저장한다")
	void storesLlmExtractedConditions() {
		String body = """
				2026학년도 2학기 성적우수 장학생을 모집합니다.
				지원자격 : 직전학기 평점평균 3.5 이상인 자, 가계 곤란으로 학업 유지가 어려운 자
				신청기간 : 2026. 8. 1. ~ 2026. 8. 14.
				""";
		String response = """
				{"title":"성적우수 장학","provider":"경희대학교","scholarshipType":"INTERNAL",
				 "documents":[],
				 "conditions":[
				   {"type":"ACADEMIC_CRITERIA","evidence":"직전학기 평점평균 3.5 이상인 자",
				    "necessity":"REQUIRED","refLabels":[],"operator":"GTE","valueInt":350,"valueIntMax":null},
				   {"type":"SPECIFIC_QUALIFICATION","evidence":"가계 곤란으로 학업 유지가 어려운 자",
				    "necessity":"PREFERRED","refLabels":[],"operator":null,"valueInt":null,"valueIntMax":null},
				   {"type":"INCOME_CRITERIA","evidence":"소득 3분위 이하만 지원할 수 있습니다",
				    "necessity":"REQUIRED","refLabels":[],"operator":"LTE","valueInt":3,"valueIntMax":null}]}
				""";
		givenPendingTargets(List.of(raw(7L, html(body), null, ParseStatus.PENDING)));
		given(llmClient.chat(any())).willReturn(response);
		given(scholarshipRepository.findByDedupKey(any())).willReturn(java.util.Optional.empty());
		given(scholarshipRepository.save(any(Scholarship.class)))
				.willAnswer(invocation -> invocation.getArgument(0));

		service.parse(20, false, false);

		var captor = org.mockito.ArgumentCaptor.forClass(List.class);
		verify(scholarshipConditionRepository).saveAll(captor.capture());
		List<ScholarshipCondition> saved = captor.getValue();

		// 본문에 없는 소득 조건은 환각으로 보고 버린다
		assertThat(saved).extracting(ScholarshipCondition::getConditionType)
				.containsExactly(ConditionType.ACADEMIC_CRITERIA, ConditionType.SPECIFIC_QUALIFICATION);
		assertThat(saved.get(0).getValueString()).isEqualTo("직전학기 평점평균 3.5 이상인 자");

		// 1단계가 본문 맥락을 보며 수치까지 뽑는다 (평점은 100배 정수 규약)
		assertThat(saved.get(0).getOperator()).isEqualTo(ConditionOperator.GTE);
		assertThat(saved.get(0).getValueInt()).isEqualTo(350);

		// 우대사항은 게이트가 아니다 — 없으면 자격 있는 학생이 탈락한다
		assertThat(saved.get(0).getNecessity()).isEqualTo(ConditionNecessity.REQUIRED);
		assertThat(saved.get(1).getNecessity()).isEqualTo(ConditionNecessity.PREFERRED);

		/*
		true 로 둬야 2단계(ConditionExtractionService)가 다시 집어가지 않는다.
		1단계는 본문 전체를 보고 뽑지만 2단계는 evidence 텍스트만 봐서, 다시 태우면
		맥락 없이 없는 숫자를 만들어낼 위험이 있다. 대학공지는 여기서 끝낸다.
		 */
		assertThat(saved.get(0).isAutoExtracted()).isTrue();
	}

	@Test
	@DisplayName("FINANCIAL_AID_TYPE 은 LLM 답변과 무관하게 우대로 강제한다 — 자격이 아니라 지원 성격이다")
	void financialAidTypeIsAlwaysPreferred() {
		String body = """
				2026학년도 2학기 생활비 지원 장학생을 모집합니다.
				지원 내용은 생활비 지원입니다. 학기당 100만원을 지급합니다.
				신청기간 : 2026. 8. 1. ~ 2026. 8. 14.
				""";
		String response = """
				{"title":"생활비 장학","provider":"경희대학교","scholarshipType":"INTERNAL","documents":[],
				 "conditions":[{"type":"FINANCIAL_AID_TYPE","evidence":"지원 내용은 생활비 지원입니다",
				   "necessity":"REQUIRED","refLabels":["생활비 지원"],
				   "operator":null,"valueInt":null,"valueIntMax":null}]}
				""";
		givenPendingTargets(List.of(raw(30L, html(body), null, ParseStatus.PENDING)));
		given(llmClient.chat(any())).willReturn(response);
		given(scholarshipRepository.findByDedupKey(any())).willReturn(java.util.Optional.empty());
		given(scholarshipRepository.save(any(Scholarship.class)))
				.willAnswer(invocation -> invocation.getArgument(0));

		service.parse(20, false, false);

		var captor = org.mockito.ArgumentCaptor.forClass(List.class);
		verify(scholarshipConditionRepository).saveAll(captor.capture());
		List<ScholarshipCondition> saved = captor.getValue();

		// LLM 이 REQUIRED 라고 답해도 우대로 내린다
		assertThat(saved.get(0).getNecessity()).isEqualTo(ConditionNecessity.PREFERRED);
	}

	@Test
	@DisplayName("재파싱은 기존 조건·서류를 지우고 다시 만든다")
	void reparseClearsOldConditionsAndDocuments() {
		Scholarship existing = existingScholarship();
		givenAllTargets(List.of(raw(3L, html(BODY), existing, ParseStatus.PARSED)));
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);

		service.parse(20, true, false);

		verify(scholarshipConditionRepository).deleteByScholarship(existing);
		verify(scholarshipDocumentRepository).deleteByScholarship(existing);
	}

	// --- dryRun ---

	@Test
	@DisplayName("dryRun 은 DB 에 쓰지 않고 비교용 결과만 돌려준다")
	void dryRunDoesNotWrite() {
		Scholarship existing = existingScholarship();
		RawScholarship target = raw(4L, html(BODY), existing, ParseStatus.PARSED);
		givenAllTargets(List.of(target));
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);

		var result = service.parse(20, true, true);

		assertThat(result.dryRun()).isTrue();
		assertThat(result.parsedCount()).isEqualTo(1);
		// 기존 값이 그대로 남아 있어야 한다
		assertThat(existing.getTitle()).isEqualTo("잘못 파싱된 제목");
		assertThat(existing.getApplicationEndAt().toLocalDate())
				.isEqualTo(java.time.LocalDate.of(2027, 2, 28));
		verify(scholarshipRepository, never()).save(any(Scholarship.class));
		verify(scholarshipConditionRepository, never()).deleteByScholarship(any());
		verify(scholarshipDocumentRepository, never()).saveAll(anyList());
		// 비교 재료는 채워져 있어야 한다
		assertThat(result.items().get(0).beforePeriod()).isEqualTo("2026-09-01 ~ 2027-02-28");
		assertThat(result.items().get(0).afterPeriod()).isEqualTo("2026-08-01 ~ 2026-08-14");
	}

	// --- 실패·건너뜀 ---

	@Test
	@DisplayName("본문을 못 뽑으면 SKIPPED 로 두고 LLM 을 호출하지 않는다")
	void skipsWhenBodyMissing() {
		RawScholarship target = raw(5L, "<html><body>첨부 참고</body></html>", null, ParseStatus.PENDING);
		givenPendingTargets(List.of(target));

		var result = service.parse(20, false, false);

		assertThat(result.skippedCount()).isEqualTo(1);
		assertThat(target.getParseStatus()).isEqualTo(ParseStatus.SKIPPED);
		verify(llmClient, never()).chat(any());
	}

	@Test
	@DisplayName("응답이 JSON 이 아니면 FAILED 로 남겨 다음 배치에서 재시도한다")
	void marksFailedOnUnparsableResponse() {
		RawScholarship target = raw(6L, html(BODY), null, ParseStatus.PENDING);
		givenPendingTargets(List.of(target));
		given(llmClient.chat(any())).willReturn("추출할 정보가 없습니다.");

		var result = service.parse(20, false, false);

		assertThat(result.failedCount()).isEqualTo(1);
		assertThat(target.getParseStatus()).isEqualTo(ParseStatus.FAILED);
	}

	@Test
	@DisplayName("형식 실패는 1회 재시도한다 — 모델이 매번 조금씩 다르게 답하므로 대개 풀린다")
	void retriesOnceOnRecoverableFailure() {
		RawScholarship target = raw(20L, html(BODY), null, ParseStatus.PENDING);
		givenPendingTargets(List.of(target));
		given(llmClient.chat(any()))
				.willThrow(new CustomException(ErrorCode.LLM_EMPTY_RESPONSE))
				.willReturn(LLM_RESPONSE);

		var result = service.parse(20, false, false);

		assertThat(result.parsedCount()).isEqualTo(1);
		verify(llmClient, times(2)).chat(any());
	}

	@Test
	@DisplayName("토큰 상한에서 잘린 응답은 재시도하지 않는다 — 같은 지점에서 다시 잘린다")
	void doesNotRetryOnTruncatedResponse() {
		RawScholarship target = raw(21L, html(BODY), null, ParseStatus.PENDING);
		givenPendingTargets(List.of(target));
		given(llmClient.chat(any())).willThrow(new CustomException(ErrorCode.LLM_RESPONSE_TRUNCATED));

		var result = service.parse(20, false, false);

		assertThat(result.failedCount()).isEqualTo(1);
		verify(llmClient, times(1)).chat(any());
		assertThat(target.getParseError()).contains("토큰 상한");
	}

	@Test
	@DisplayName("본문이 잘린 채 실패하면 사유에 그 사실을 남긴다 — 재시도 대상에서 빼기 위함")
	void recordsBodyTruncationInFailureReason() {
		String longBody = "장학금 신청 안내입니다. ".repeat(2000);
		RawScholarship target = raw(22L, html(longBody), null, ParseStatus.PENDING);
		givenPendingTargets(List.of(target));
		given(llmClient.chat(any())).willReturn("JSON 이 아닙니다");

		service.parse(20, false, false);

		assertThat(target.getParseError()).contains("잘린 상태");
	}

	@Test
	@DisplayName("성공하면 파싱 이력에 정제 결과를, 실패하면 응답 원문을 남긴다")
	void savesParseLog() {
		RawScholarship ok = raw(23L, html(BODY), null, ParseStatus.PENDING);
		givenPendingTargets(List.of(ok));
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);

		service.parse(20, false, false);

		ArgumentCaptor<NoticeParseLog> captor = ArgumentCaptor.forClass(NoticeParseLog.class);
		verify(noticeParseLogRepository).save(captor.capture());
		NoticeParseLog log = captor.getValue();
		assertThat(log.getStatus()).isEqualTo(ParseStatus.PARSED);
		assertThat(log.getParsedJson()).contains("운연장학");
		// 성공 건은 정제 결과에서 언제든 되돌릴 수 있어 원문을 중복 저장하지 않는다
		assertThat(log.getRawResponse()).isNull();
		assertThat(log.getPromptVersion()).isEqualTo(UnivNoticeLlmParser.PROMPT_VERSION);
		assertThat(log.isBodyTruncated()).isFalse();
	}

	@Test
	@DisplayName("dryRun 은 파싱 이력도 남기지 않는다")
	void dryRunSavesNoLog() {
		RawScholarship target = raw(24L, html(BODY), null, ParseStatus.PENDING);
		givenPendingTargets(List.of(target));
		given(llmClient.chat(any())).willReturn(LLM_RESPONSE);

		service.parse(20, false, true);

		verify(noticeParseLogRepository, never()).save(any());
	}

	@Test
	@DisplayName("한 건이 예외로 실패해도 나머지는 계속 처리한다")
	void continuesAfterException() {
		RawScholarship bad = raw(7L, html(BODY), null, ParseStatus.PENDING);
		RawScholarship good = raw(8L, html(BODY), null, ParseStatus.PENDING);
		givenPendingTargets(List.of(bad, good));
		given(llmClient.chat(any()))
				.willThrow(new RuntimeException("크레딧 부족"))
				.willReturn(LLM_RESPONSE);
		given(scholarshipRepository.findByDedupKey(any())).willReturn(Optional.empty());
		given(scholarshipRepository.save(any(Scholarship.class)))
				.willAnswer(invocation -> invocation.getArgument(0));

		var result = service.parse(20, false, false);

		assertThat(result.failedCount()).isEqualTo(1);
		assertThat(result.parsedCount()).isEqualTo(1);
		assertThat(bad.getParseStatus()).isEqualTo(ParseStatus.FAILED);
		assertThat(good.getParseStatus()).isEqualTo(ParseStatus.PARSED);
	}

	// --- 배치 상한 ---

	@Test
	@DisplayName("limit 은 1~100 으로 제한한다 — 크레딧 순삭 방지")
	void clampsBatchSize() {
		givenPendingTargets(List.of());

		service.parse(9999, false, false);
		service.parse(0, false, false);

		var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
		verify(rawScholarshipRepository, org.mockito.Mockito.times(2))
				.findBySourceStartingWithAndParseStatusOrderByIdAsc(any(), any(), captor.capture());
		assertThat(captor.getAllValues().get(0).getPageSize()).isEqualTo(100);
		assertThat(captor.getAllValues().get(1).getPageSize()).isEqualTo(1);
	}

	// --- Reflection helper (엔티티 ID 는 setter 가 없어 리플렉션으로 주입) ---

	private static void setField(Object target, String name, Object value) {
		try {
			Field field = findField(target.getClass(), name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
		Class<?> current = clazz;
		while (current != null && current != Object.class) {
			try {
				return current.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
				current = current.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}
}
