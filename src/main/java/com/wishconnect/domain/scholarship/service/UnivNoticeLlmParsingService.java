package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.scholarship.dto.NoticeParsingResponse;
import com.wishconnect.domain.scholarship.dto.ParsedNotice;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
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
import com.wishconnect.domain.scholarship.util.UnivNoticeLlmParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
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
	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final ScholarshipDocumentRepository scholarshipDocumentRepository;
	private final UnivNoticeLlmParser parser;
	private final LlmClient llmClient;

	/**
	 * 대학 장학공지를 LLM 으로 파싱한다.
	 *
	 * @param limit   처리 건수 (1 ~ {@value #MAX_BATCH_SIZE})
	 * @param reparse true 면 이미 파싱된 것까지 다시 파싱해 덮어쓴다
	 * @param dryRun  true 면 DB 에 쓰지 않고 결과만 반환한다
	 */
	@Transactional
	public NoticeParsingResponse parse(int limit, boolean reparse, boolean dryRun) {
		int size = Math.min(Math.max(limit, 1), MAX_BATCH_SIZE);
		var page = PageRequest.of(0, size);
		List<RawScholarship> targets = reparse
				? rawScholarshipRepository.findBySourceStartingWithOrderByIdAsc(UNIV_SOURCE_PREFIX, page)
				: rawScholarshipRepository.findBySourceStartingWithAndParseStatusOrderByIdAsc(
						UNIV_SOURCE_PREFIX, ParseStatus.PENDING, page);

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
					case SKIPPED -> skipped++;
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

	private Outcome parseOne(RawScholarship raw, boolean dryRun) {
		String beforePeriod = describePeriod(raw.getScholarship());

		Optional<String> body = parser.extractBody(raw.getRawHtml());
		if (body.isEmpty()) {
			if (!dryRun) {
				raw.markSkipped("본문을 추출할 수 없습니다(첨부·이미지 전용 공지로 추정).");
			}
			return new Outcome(ParseStatus.SKIPPED,
					item(raw, "SKIPPED", null, beforePeriod, "본문 없음"));
		}

		String bodyText = body.get();
		String response = llmClient.chat(parser.buildRequest(bodyText));
		Optional<ParsedNotice> maybeNotice = parser.readResponse(response);
		if (maybeNotice.isEmpty()) {
			if (!dryRun) {
				raw.markFailed("LLM 응답을 JSON 으로 읽지 못했습니다.");
			}
			return new Outcome(ParseStatus.FAILED,
					item(raw, "FAILED", null, beforePeriod, "응답 파싱 실패"));
		}

		ParsedNotice notice = maybeNotice.get();
		Optional<UnivNoticeLlmParser.Period> period = parser.resolvePeriod(notice, bodyText);
		String title = firstNonBlank(notice.title(), fallbackTitle(raw));
		String afterPeriod = period.map(p -> format(p.start()) + " ~ " + format(p.end())).orElse(null);
		String note = period.isEmpty() ? "기간 미확보(근거 없음·라벨 불일치·범위 초과 중 하나)" : null;

		if (dryRun) {
			return new Outcome(ParseStatus.PARSED,
					item(raw, "PARSED", title, beforePeriod, afterPeriod, note));
		}

		Scholarship scholarship = upsert(raw, notice, title, bodyText, period.orElse(null));
		raw.markParsed(scholarship);
		storeConditions(scholarship, parser.resolveConditions(notice, bodyText));
		storeDocuments(scholarship, notice.safeDocuments());

		return new Outcome(ParseStatus.PARSED,
				item(raw, "PARSED", title, beforePeriod, afterPeriod, note));
	}

	/**
	 * 정제 데이터를 만들거나 덮어쓴다.
	 *
	 * <p>이미 이 원본에 연결된 장학금이 있으면(재파싱) 그 행을 갱신한다. dedupKey 로 새로 찾지 않는
	 * 이유는, 재파싱으로 제목·기간이 바뀌면 dedupKey 도 바뀌어 같은 공고에 행이 하나 더 생기기 때문이다.
	 */
	private Scholarship upsert(RawScholarship raw, ParsedNotice notice, String title,
			String bodyText, UnivNoticeLlmParser.Period period) {

		String provider = firstNonBlank(notice.provider(), raw.getSource());
		ScholarshipType type = parser.resolveType(notice.scholarshipType());
		LocalDateTime startAt = period == null ? null : period.start();
		LocalDateTime endAt = period == null ? null : period.end();
		String description = bodyText.length() > MAX_DESCRIPTION_CHARS
				? bodyText.substring(0, MAX_DESCRIPTION_CHARS)
				: bodyText;

		Scholarship existing = raw.getScholarship();
		if (existing != null) {
			existing.applyLlmParsed(cleanTitle(title), provider, notice.summary(), description,
					type, startAt, endAt,
					parser.resolveSelectionCount(notice.selectionCount()),
					parser.resolveAmount(notice.amount()),
					raw.getSourceUrl());
			// 재파싱은 조건·서류를 다시 만든다. 옛 값이 남으면 새 파싱 결과와 섞인다.
			scholarshipConditionRepository.deleteByScholarship(existing);
			scholarshipDocumentRepository.deleteByScholarship(existing);
			return existing;
		}

		String dedupKey = dedupKey(raw.getSource(), title, startAt, endAt);
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
				.map(condition -> ScholarshipCondition.builder()
						.scholarship(scholarship)
						.conditionType(condition.type())
						.operator(ConditionOperator.EQ)
						.valueString(condition.snippet())
						.autoExtracted(false)
						.build())
				.toList();
		scholarshipConditionRepository.saveAll(conditions);
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
		return item(raw, status, title, beforePeriod, null, note);
	}

	private NoticeParsingResponse.Item item(RawScholarship raw, String status, String title,
			String beforePeriod, String afterPeriod, String note) {
		return new NoticeParsingResponse.Item(raw.getId(), raw.getSource(), raw.getSourceUrl(),
				status, title, beforePeriod, afterPeriod, note);
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
	private static String fallbackTitle(RawScholarship raw) {
		return raw.getSource() + " 공고 " + raw.getSourceId();
	}

	private static String cleanTitle(String title) {
		String cleaned = title.replaceAll("\\s+", " ").trim();
		return cleaned.length() > 490 ? cleaned.substring(0, 490) : cleaned;
	}

	private static String firstNonBlank(String value, String fallback) {
		return value != null && !value.isBlank() ? value.trim() : fallback;
	}

	/** 수집기와 같은 방식으로 만든다. 재파싱이 아닌 신규 저장에서만 쓴다. */
	private static String dedupKey(String source, String title,
			LocalDateTime startAt, LocalDateTime endAt) {
		String seed = source + "|" + title + "|"
				+ (startAt == null && endAt == null ? "" : startAt + "~" + endAt);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of()
					.formatHex(digest.digest(seed.getBytes(StandardCharsets.UTF_8)))
					.substring(0, 64);
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 사용 불가", e);
		}
	}

	private record Outcome(ParseStatus status, NoticeParsingResponse.Item item) {
	}
}
