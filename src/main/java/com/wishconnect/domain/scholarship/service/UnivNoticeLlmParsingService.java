package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.scholarship.dto.NoticeParsingResponse;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.dto.ParsedNotice;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.config.LlmProperties;
import com.wishconnect.domain.scholarship.entity.NoticeParseLog;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.repository.NoticeParseLogRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.entity.ScholarshipDocument;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.util.NoticeHtmlExtractor;
import com.wishconnect.domain.scholarship.util.ScholarshipDedupKey;
import com.wishconnect.domain.scholarship.util.UnivNoticeLlmParser;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
대학 장학공지(UNIV_*)의 raw_html 을 LLM 으로 파싱해 scholarship 으로 정제하는 서비스입니다.

공공데이터 포털(KOSAF 등)은 응답이 이미 구조화돼 있어 LLM 이 필요 없습니다. 기존
ScholarshipSyncService 가 그대로 처리하고, 이 서비스는 source 가 UNIV_ 로 시작하는 것만 봅니다.

두 가지 모드가 있습니다.
- 신규 파싱: parse_status = PENDING 인 것만
- 재파싱   : 상태와 무관하게 전체. 정규식으로 잘못 파싱된 기존 데이터를 덮어씁니다.

dryRun 을 켜면 DB 에 쓰지 않고 결과만 돌려줍니다. 정규식 결과와 LLM 결과를 사람이 비교해
전환 여부를 판단하는 단계에 쓰기 위한 것입니다(LLM 자기채점은 순환이라 쓰지 않습니다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnivNoticeLlmParsingService {

	/** 대학 크롤링 출처의 공통 접두사. 공공데이터와 구분하는 기준이다. */
	private static final String UNIV_SOURCE_PREFIX = "UNIV_";

	/**
	 * 한 번에 처리할 최대 건수.
	 * 첫 실행에서 수천 건을 한 번에 돌리면 크레딧이 순식간에 소진되므로 상한을 둔다.
	 */
	private static final int MAX_BATCH_SIZE = 100;
	private static final int DEFAULT_BATCH_SIZE = 20;

	/** description 컬럼에 남길 본문 길이. 상세 화면 노출용이라 전문이 필요하지 않다. */
	private static final int MAX_DESCRIPTION_CHARS = 2_000;

	private static final Pattern ESSAY_DOCUMENT = Pattern.compile(
			"(자기\\s*소개서|자소서|학업\\s*계획서|수학\\s*계획서|전인적\\s*성장\\s*계획서|에세이|essay)",
			Pattern.CASE_INSENSITIVE);
	private static final int MAX_DOCUMENTS = 12;

	private final RawScholarshipRepository rawScholarshipRepository;
	// 포스터는 수집기가 아니라 여기서 붙인다 — 수집 시점에는 아직 scholarship 이 없다.
	private final ImageStorageService imageStorageService;
	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final ScholarshipDocumentRepository scholarshipDocumentRepository;
	private final UnivNoticeLlmParser parser;
	private final LlmClient llmClient;
	// 파싱 1회를 기록해 정확도 측정·실패 원인 추적에 쓴다.
	private final NoticeParseLogRepository noticeParseLogRepository;
	private final LlmProperties llmProperties;
	private final ObjectMapper objectMapper;
	private final ConditionRefResolver conditionRefResolver;

	/**
	 * 대학 장학공지를 LLM 으로 파싱한다.
	 *
	 * @param limit   처리 건수 (1 ~ {@value #MAX_BATCH_SIZE})
	 * @param reparse true 면 이미 파싱된 것까지 다시 파싱해 덮어쓴다
	 * @param dryRun  true 면 DB 에 쓰지 않고 결과만 반환한다
	 */
	@Transactional
	public NoticeParsingResponse parse(int limit, boolean reparse, boolean dryRun) {
		return parse(limit, reparse, dryRun, List.of());
	}

	/**
	 * @param rawIds 비어 있지 않으면 <b>이 공지들만</b> 처리한다. 프롬프트 버전 필터를 건너뛴다.
	 *               추출기를 고쳐 같은 공지의 결과가 달라질 때 쓴다.
	 */
	@Transactional
	public NoticeParsingResponse parse(int limit, boolean reparse, boolean dryRun, List<Long> rawIds) {
		return parse(limit, reparse, dryRun, rawIds, true);
	}

	/**
	 * @param skipComplete 이미 제목·마감일이 제대로 들어간 공고를 건너뛴다(기본). 결과가 좋아질
	 *                     여지가 없는 건에 크레딧을 쓰지 않기 위해서다. 프롬프트를 크게 바꿔
	 *                     전부 다시 보고 싶을 때만 false 로 둔다.
	 */
	@Transactional
	public NoticeParsingResponse parse(int limit, boolean reparse, boolean dryRun, List<Long> rawIds,
			boolean skipComplete) {
		int size = Math.min(Math.max(limit, 1), MAX_BATCH_SIZE);
		var page = PageRequest.of(0, size);
		if (rawIds != null && !rawIds.isEmpty()) {
			List<RawScholarship> picked = rawScholarshipRepository.findByIdInOrderByIdAsc(
					rawIds.stream().distinct().limit(size).toList());
			return run(picked, reparse, dryRun);
		}
		List<RawScholarship> targets = reparse
				? (skipComplete
						? rawScholarshipRepository.findIncompleteReparseTargets(
								UNIV_SOURCE_PREFIX, UnivNoticeLlmParser.PROMPT_VERSION, page)
						: rawScholarshipRepository.findReparseTargets(
								UNIV_SOURCE_PREFIX, UnivNoticeLlmParser.PROMPT_VERSION, page))
				: rawScholarshipRepository.findBySourceStartingWithAndParseStatusOrderByIdAsc(
						UNIV_SOURCE_PREFIX, ParseStatus.PENDING, page);
		return run(targets, reparse, dryRun);
	}

	/** 대상이 정해진 뒤의 처리. 어떻게 골랐든 이 다음은 같다. */
	private NoticeParsingResponse run(List<RawScholarship> targets, boolean reparse, boolean dryRun) {
		int parsed = 0;
		int skipped = 0;
		int failed = 0;
		List<NoticeParsingResponse.Item> items = new ArrayList<>();

		for (RawScholarship raw : targets) {
			try {
				Outcome outcome = parseOne(raw, dryRun);
				items.add(outcome.item());
				switch (outcome.status()) {
					case PARSED -> parsed++;
					case SKIPPED, IMAGE_ONLY -> skipped++;
					default -> failed++;
				}
			} catch (Exception e) {
				// 한 건이 실패해도 배치 전체를 멈추지 않는다. 다음 실행에서 재시도된다.
				log.warn("[UnivLlmParsing] 파싱 실패 rawId={} : {}", raw.getId(), e.getMessage());
				if (!dryRun) {
					raw.markFailed("LLM 파싱 실패: " + e.getMessage());
				}
				failed++;
				items.add(item(raw, "FAILED", null, null, e.getMessage()));
			}
		}

		log.info("[UnivLlmParsing] 대상={} 정제={} 스킵={} 실패={} reparse={} dryRun={}",
				targets.size(), parsed, skipped, failed, reparse, dryRun);
		return new NoticeParsingResponse(targets.size(), parsed, skipped, failed, dryRun, items);
	}


	/**
	 * LLM 호출. 형식 오류로 보이는 실패만 한 번 더 시도한다.
	 *
	 * <p>재시도가 의미 있는 실패와 없는 실패를 나눈다. 응답이 {@code max_tokens} 에서 잘린 경우는
	 * 같은 요청이 같은 지점에서 다시 잘리므로 재시도가 토큰만 태운다. 반면 형식이 깨지거나
	 * 일시적 오류로 실패한 경우는 모델이 매번 조금씩 다르게 답하므로 한 번 더 부르면 대개 풀린다.
	 */
	private String callWithRetry(String noticeTitle, String bodyText, Long rawId) {
		try {
			return llmClient.chat(parser.buildRequest(noticeTitle, bodyText));
		} catch (CustomException e) {
			if (e.getErrorCode() == ErrorCode.LLM_RESPONSE_TRUNCATED) {
				throw e;
			}
			log.info("[UnivLlmParsing] LLM 호출 실패, 1회 재시도합니다. rawId={} reason={}",
					rawId, e.getErrorCode());
			return llmClient.chat(parser.buildRequest(noticeTitle, bodyText));
		}
	}

	/** 실패 사유를 사람이 읽고 조치할 수 있는 문장으로 만든다. */
	private String describeLlmFailure(CustomException e, UnivNoticeLlmParser.ExtractedBody extracted) {
		if (e.getErrorCode() == ErrorCode.LLM_RESPONSE_TRUNCATED) {
			return "LLM 응답이 토큰 상한에서 잘렸습니다(재시도 무의미, max_tokens 상향 필요).";
		}
		if (extracted.truncated()) {
			return "본문이 상한을 넘어 잘린 상태에서 LLM 호출 실패: " + e.getErrorCode();
		}
		return "LLM 호출 실패: " + e.getErrorCode();
	}

	/**
	 * 파싱 1회를 기록한다. 실패해도 배치를 멈추지 않는다 — 기록이 본 작업을 막으면 안 된다.
	 *
	 * <p>성공은 정제 결과를, 실패는 응답 원문을 남긴다. 실패 시엔 정제 객체가 없으므로
	 * 원문이 유일한 단서다.
	 */
	private void saveLog(RawScholarship raw, UnivNoticeLlmParser.ExtractedBody extracted,
			ParseStatus status, ParsedNotice notice, String rawResponse, String message) {
		try {
			String parsedJson = notice == null ? null : objectMapper.writeValueAsString(notice);
			noticeParseLogRepository.save(NoticeParseLog.builder()
					.rawScholarshipId(raw.getId())
					.status(status)
					.modelId(llmProperties.parserModel())
					.promptVersion(UnivNoticeLlmParser.PROMPT_VERSION)
					.bodyTruncated(extracted.truncated())
					.bodyLength(extracted.originalLength())
					.parsedJson(parsedJson)
					.rawResponse(rawResponse)
					.errorMessage(message)
					.build());
		} catch (Exception e) {
			log.warn("[UnivLlmParsing] 파싱 이력 저장 실패 rawId={} : {}", raw.getId(), e.getMessage());
		}
	}

	private Outcome parseOne(RawScholarship raw, boolean dryRun) {
		String beforePeriod = describePeriod(raw.getScholarship());

		Optional<UnivNoticeLlmParser.ExtractedBody> body = parser.extractBody(raw.getRawHtml());
		if (body.isEmpty()) {
			// 포스터 이미지뿐인 공지는 따로 표시한다. 내용이 없는 게 아니라 형식이 달라서,
			// 나중에 OCR·이미지 모델을 붙이면 살릴 수 있는 대상이다.
			boolean imageOnly = parser.isImageOnly(raw.getRawHtml());
			String reason = imageOnly
					? "본문이 포스터 이미지뿐입니다(OCR·이미지 모델 대상)."
					: "본문을 추출할 수 없습니다.";
			ParseStatus status = imageOnly ? ParseStatus.IMAGE_ONLY : ParseStatus.SKIPPED;
			if (!dryRun) {
				raw.markSkipped(reason, status);
			}
			return new Outcome(status,
					item(raw, status.name(), null, beforePeriod, reason));
		}

		UnivNoticeLlmParser.ExtractedBody extracted = body.get();
		String bodyText = extracted.text();
		if (extracted.truncated()) {
			log.info("[UnivLlmParsing] 본문이 잘렸습니다. rawId={} 원본={}자", raw.getId(), extracted.originalLength());
		}

		String htmlTitle = parser.extractTitle(raw.getRawHtml()).orElse(null);

		String response;
		try {
			response = callWithRetry(htmlTitle, bodyText, raw.getId());
		} catch (CustomException e) {
			String reason = describeLlmFailure(e, extracted);
			if (!dryRun) {
				raw.markFailed(reason);
				saveLog(raw, extracted, ParseStatus.FAILED, null, null, reason);
			}
			return new Outcome(ParseStatus.FAILED, item(raw, "FAILED", null, beforePeriod, reason));
		}

		Optional<ParsedNotice> maybeNotice = parser.readResponse(response);
		if (maybeNotice.isEmpty()) {
			String reason = extracted.truncated()
					? "본문이 상한을 넘어 잘린 상태에서 응답 파싱 실패(재시도 무의미)."
					: "LLM 응답을 JSON 으로 읽지 못했습니다.";
			if (!dryRun) {
				raw.markFailed(reason);
				saveLog(raw, extracted, ParseStatus.FAILED, null, response, reason);
			}
			return new Outcome(ParseStatus.FAILED, item(raw, "FAILED", null, beforePeriod, reason));
		}

		ParsedNotice notice = maybeNotice.get();
		Optional<UnivNoticeLlmParser.Period> period = parser.resolvePeriod(notice, bodyText);
		// LLM 이 제목을 못 냈으면 게시판에서 뽑은 제목을 쓴다. 출처·번호로 만든 이름은 마지막 수단이다.
		String title = firstNonBlank(notice.title(), htmlTitle, fallbackTitle(raw));
		String afterPeriod = period.map(p -> format(p.start()) + " ~ " + format(p.end())).orElse(null);
		String note = period.isEmpty() ? "기간 미확보(근거 없음·라벨 불일치·범위 초과 중 하나)" : null;

		// dryRun 도 조건·서류·포스터를 세어 돌려준다. 제목과 기간만 보면 파서가 하는 일의
		// 절반밖에 확인할 수 없다 — 조건 추출이 통째로 망가져도 멀쩡해 보인다.
		int conditionCount = parser.resolveConditions(notice, bodyText).size();
		int documentCount = notice.safeDocuments().size();
		boolean posterFound = NoticeHtmlExtractor.posterUrl(
				org.jsoup.Jsoup.parse(raw.getRawHtml(),
						raw.getSourceUrl() == null ? "" : raw.getSourceUrl())) != null;

		if (dryRun) {
			return new Outcome(ParseStatus.PARSED, item(raw, "PARSED", title, beforePeriod,
					afterPeriod, conditionCount, documentCount, posterFound, note));
		}

		Scholarship scholarship = upsert(raw, notice, title, bodyText, period.orElse(null), htmlTitle);
		raw.markParsed(scholarship);
		saveLog(raw, extracted, ParseStatus.PARSED, notice, null, note);
		storeConditions(scholarship, parser.resolveConditions(notice, bodyText));
		storeDocuments(scholarship, notice.safeDocuments());
		storePoster(raw, scholarship, title);

		return new Outcome(ParseStatus.PARSED, item(raw, "PARSED", title, beforePeriod,
				afterPeriod, conditionCount, documentCount, posterFound, note));
	}

	/**
	 * 정제 데이터를 만들거나 덮어쓴다.
	 *
	 * <p>이미 이 원본에 연결된 장학금이 있으면(재파싱) 그 행을 갱신한다. dedupKey 로 새로 찾지 않는
	 * 이유는, 재파싱으로 제목·기간이 바뀌면 dedupKey 도 바뀌어 같은 공고에 행이 하나 더 생기기 때문이다.
	 */
	private Scholarship upsert(RawScholarship raw, ParsedNotice notice, String title,
			String bodyText, UnivNoticeLlmParser.Period period, String noticeTitle) {
		UnivNoticeLlmParser.Requirement essay = parser.resolveRequirement(
				notice.essayRequirement(), notice.essayEvidence(), bodyText, noticeTitle);
		UnivNoticeLlmParser.Requirement interview = parser.resolveRequirement(
				notice.interviewRequirement(), notice.interviewEvidence(), bodyText, noticeTitle);

		String provider = firstNonBlank(notice.provider(), raw.getSource());
		ScholarshipType type = parser.resolveType(notice.scholarshipType());
		LocalDateTime startAt = period == null ? null : period.start();
		LocalDateTime endAt = period == null ? null : period.end();
		String description = bodyText.length() > MAX_DESCRIPTION_CHARS
				? bodyText.substring(0, MAX_DESCRIPTION_CHARS)
				: bodyText;

		Scholarship existing = shareableTarget(raw);
		if (existing != null) {
			existing.applyLlmParsed(cleanTitle(title), provider, notice.summary(), description,
					type, startAt, endAt,
					parser.resolveSelectionCount(notice.selectionCount()),
					parser.resolveAmount(notice.amount()),
					raw.getSourceUrl(),
					essay.level(), essay.evidence(), interview.level(), interview.evidence());
			// 재파싱은 조건·서류를 다시 만든다. 옛 값이 남으면 새 파싱 결과와 섞인다.
			scholarshipConditionRepository.deleteByScholarship(existing);
			scholarshipDocumentRepository.deleteByScholarship(existing);
			return existing;
		}

		String dedupKey = ScholarshipDedupKey.of(raw.getSource(), raw.getSourceId());
		return scholarshipRepository.findByDedupKey(dedupKey).orElseGet(() ->
				scholarshipRepository.save(Scholarship.builder()
						.title(cleanTitle(title))
						.provider(provider)
						.summary(notice.summary())
						.description(description)
						.scholarshipType(type)
						.applicationStartAt(startAt)
						.applicationEndAt(endAt)
						.recruitmentStatus(resolveStatus(startAt))
						.selectionCount(parser.resolveSelectionCount(notice.selectionCount()))
						.amount(parser.resolveAmount(notice.amount()))
						.primarySource(raw.getSource())
						.essayRequirement(essay.level())
						.essayEvidence(essay.evidence())
						.interviewRequirement(interview.level())
						.interviewEvidence(interview.evidence())
						.dedupKey(dedupKey)
						.homepageUrl(raw.getSourceUrl())
						.build()));
	}

	/**
	 * 자격조건을 저장한다. 유형 판별과 문장 선별은 LLM 이 하고, 검증은 파서가 끝낸 상태로 들어온다.
	 *
	 * <p>정규식 추출기는 미리 정한 패턴에 걸리는 문장만 잡아서, 대학 공지의 서술형 자격 요건
	 * (예: "가계 곤란으로 학업 유지가 어려운 자")을 통째로 놓쳤다. 문맥 판별은 LLM 이 낫다.
	 *
	 * <p>수치 구조화(valueInt)는 여기서 하지 않는다. {@code autoExtracted=false} 로 두면
	 * ConditionExtractionService 가 대상으로 집어가 기존 파이프라인이 그대로 이어진다.
	 */
	private void storeConditions(Scholarship scholarship,
			List<UnivNoticeLlmParser.ResolvedCondition> resolved) {
		if (resolved.isEmpty()) {
			return;
		}
		List<ScholarshipCondition> conditions = resolved.stream()
				.map(condition -> toEntity(scholarship, condition))
				.toList();
		scholarshipConditionRepository.saveAll(conditions);
	}


	/**
	 * 1단계 결과를 조건 행으로 만든다.
	 *
	 * <p>{@code autoExtracted = true} 로 둔다. 이 값의 실제 의미는 "수치 구조화를 시도했는가"이고,
	 * 2단계(ConditionExtractionService)가 {@code autoExtracted=false AND value_int IS NULL} 로
	 * 대상을 고르기 때문이다. 1단계가 본문 맥락을 보며 값까지 뽑았으므로, 값을 못 찾았더라도
	 * evidence 만 보는 2단계가 다시 집어가면 오히려 없는 숫자를 만들어낼 위험이 있다.
	 * 그래서 대학공지는 여기서 끝내고 2단계는 공공데이터 전용으로 남긴다.
	 */
	private ScholarshipCondition toEntity(Scholarship scholarship,
			UnivNoticeLlmParser.ResolvedCondition condition) {
		ScholarshipCondition entity = ScholarshipCondition.builder()
				.scholarship(scholarship)
				.conditionType(condition.type())
				.necessity(condition.necessity())
				.operator(condition.operator())
				.valueInt(condition.valueInt())
				.valueIntMax(condition.valueIntMax())
				.valueString(condition.snippet())
				.autoExtracted(true)
				.build();
		entity.applyRefs(conditionRefResolver.resolve(condition.type(), condition.refLabels()));
		return entity;
	}

	/** 제출서류는 LLM 이 뽑은 목록을 쓴다. 서류명은 문맥 이해가 필요해 정규식보다 LLM 이 낫다. */
	private void storeDocuments(Scholarship scholarship, List<String> names) {
		LinkedHashSet<String> unique = new LinkedHashSet<>();
		for (String name : names) {
			String cleaned = name == null ? "" : name.replaceAll("\\s+", " ").trim();
			if (cleaned.length() >= 2 && cleaned.length() <= 80) {
				unique.add(cleaned);
			}
			if (unique.size() >= MAX_DOCUMENTS) {
				break;
			}
		}
		if (unique.isEmpty()) {
			return;
		}
		List<ScholarshipDocument> documents = new ArrayList<>();
		int order = 0;
		for (String name : unique) {
			documents.add(ScholarshipDocument.builder()
					.scholarship(scholarship)
					.name(name)
					.essay(ESSAY_DOCUMENT.matcher(name).find())
					.displayOrder(order++)
					.build());
		}
		scholarshipDocumentRepository.saveAll(documents);
	}

	private RecruitmentStatus resolveStatus(LocalDateTime startAt) {
		if (startAt != null && LocalDateTime.now().isBefore(startAt)) {
			return RecruitmentStatus.UPCOMING;
		}
		return RecruitmentStatus.OPEN;
	}

	private NoticeParsingResponse.Item item(RawScholarship raw, String status, String title,
			String beforePeriod, String note) {
		return item(raw, status, title, beforePeriod, null, 0, 0, false, note);
	}

	private NoticeParsingResponse.Item item(RawScholarship raw, String status, String title,
			String beforePeriod, String afterPeriod, int conditionCount, int documentCount,
			boolean posterFound, String note) {
		return new NoticeParsingResponse.Item(raw.getId(), raw.getSource(), raw.getSourceUrl(),
				status, title, beforePeriod, afterPeriod,
				conditionCount, documentCount, posterFound, note);
	}

	private static String describePeriod(Scholarship scholarship) {
		if (scholarship == null) {
			return null;
		}
		if (scholarship.getApplicationStartAt() == null && scholarship.getApplicationEndAt() == null) {
			return null;
		}
		return format(scholarship.getApplicationStartAt()) + " ~ "
				+ format(scholarship.getApplicationEndAt());
	}

	private static String format(LocalDateTime value) {
		return value == null ? "" : value.toLocalDate().toString();
	}

	/** LLM 이 제목을 못 뽑은 경우의 최후 수단. 제목은 NOT NULL 이라 비울 수 없다. */
	/** 마지막 수단. 여기까지 오면 사용자에게 "UNIV_KONKUK 공고 1200120" 이 보인다. */
	private static String fallbackTitle(RawScholarship raw) {
		return raw.getSource() + " 공고 " + raw.getSourceId();
	}

	private static String cleanTitle(String title) {
		String cleaned = title.replaceAll("\\s+", " ").trim();
		return cleaned.length() > 490 ? cleaned.substring(0, 490) : cleaned;
	}

	/** 앞에서부터 비어 있지 않은 첫 값. 제목은 LLM → 게시판 → 출처·번호 순으로 떨어진다. */
	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value.trim();
			}
		}
		return null;
	}

	/**
	 * 이 공지가 <b>단독으로</b> 쓰는 장학금 행. 다른 공지와 공유 중이면 null 을 내 새 행을 만들게 한다.
	 *
	 * <p>운영에서 한 장학금 행을 공지 9건이 함께 가리키는 것을 발견했다. 정규식 파서가 제목을
	 * 못 뽑아 페이지의 공유 버튼 문구("대학공지 공유팝업 열기 카카오 공유하기…")를 제목으로 넣는
	 * 바람에, 서로 다른 공지가 같은 제목 → 같은 dedupKey 로 한 행에 묶인 것이다.
	 *
	 * <p>이 상태로 재파싱하면 <b>같은 행을 순서대로 덮어써</b> 마지막 공지만 남는다. 실제로
	 * 앞서 뽑아낸 모집기간이 뒤 공지의 빈 값에 지워졌다. 공유를 끊고 각자 행을 갖게 해야 한다 —
	 * LLM 은 제목·기간을 제대로 뽑으므로 새 dedupKey 는 서로 갈린다.
	 *
	 * <p>공유를 끊어도 옛 행은 남는다. 아무 공지도 가리키지 않게 되면 관리자 화면에서 정리한다.
	 */
	/**
	 * 공지에 실린 포스터 이미지를 장학금에 붙인다.
	 *
	 * <p>예전에는 수집기가 했다. 수집기가 더는 {@code scholarship} 을 만들지 않으므로
	 * 붙일 대상이 생기는 시점인 여기로 옮겼다.
	 *
	 * <p>실패해도 파싱을 깨뜨리지 않는다. 포스터가 없으면 카드가 밋밋할 뿐이지만,
	 * 이미지 하나 때문에 공고 자체가 안 들어오면 그게 더 큰 손해다.
	 */
	private void storePoster(RawScholarship raw, Scholarship scholarship, String title) {
		try {
			String baseUri = raw.getSourceUrl() == null ? "" : raw.getSourceUrl();
			String posterUrl = NoticeHtmlExtractor.posterUrl(
					org.jsoup.Jsoup.parse(raw.getRawHtml(), baseUri));
			if (posterUrl == null) {
				return;
			}
			imageStorageService.storeFromUrl(posterUrl, "scholarship/llm",
					ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarship.getId(), title);
		} catch (RuntimeException e) {
			log.warn("[UnivLlmParsing] 포스터 저장 실패(무시). rawId={} 사유={}", raw.getId(), e.getMessage());
		}
	}

	private Scholarship shareableTarget(RawScholarship raw) {
		Scholarship existing = raw.getScholarship();
		if (existing == null) {
			return null;
		}
		long sharedBy = rawScholarshipRepository.countByScholarship(existing);
		if (sharedBy > 1) {
			log.warn("[UnivLlmParsing] 장학금 {}번을 공지 {}건이 공유 중이라 분리합니다. rawId={}",
					existing.getId(), sharedBy, raw.getId());
			return null;
		}
		return existing;
	}


	private record Outcome(ParseStatus status, NoticeParsingResponse.Item item) {
	}
}
