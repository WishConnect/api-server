package com.wishconnect.domain.scholarship.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.scholarship.dto.MergeCandidateResponse;
import com.wishconnect.domain.scholarship.dto.MergeDetectionResponse;
import com.wishconnect.domain.scholarship.dto.MergeResultResponse;
import com.wishconnect.domain.scholarship.entity.MergeCandidateStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipMergeCandidate;
import com.wishconnect.domain.scholarship.repository.ScholarshipMergeCandidateRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.util.ScholarshipTitleBlocker;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
중복 장학금을 탐지해 승인 큐에 올리고, 사람이 승인하면 병합을 실행하는 서비스입니다.

흐름
  1. blocking  제목을 정규화해 같은 공고일 가능성이 있는 것끼리 묶는다 (규칙, LLM 없음)
  2. LLM 판정  묶인 그룹만 넘겨 실제 중복인지 확인한다 (그룹당 1회 호출)
  3. 승인 대기  PENDING 으로 큐에 올린다. 여기까지는 아무것도 바꾸지 않는다
  4. 사람 승인  어드민이 승인하면 병합, 반려하면 REJECTED 로 남긴다

LLM 판정을 곧바로 실행하지 않는 이유는, 병합이 되돌리기 어렵기 때문입니다. 스크랩·자소서가
다른 장학금으로 옮겨가므로 오판으로 멀쩡한 장학금을 지우면 복구가 번거롭습니다.
실측에서도 캠퍼스만 다른 별개 모집(복지장학금 서울/다빈치)이 후보로 올라왔습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScholarshipDedupService {

	/** 한 번에 검사할 장학금 수 상한. 그룹 수만큼 LLM 을 호출하므로 크레딧 방어가 필요하다. */
	private static final int MAX_SCAN_SIZE = 500;
	private static final int DEFAULT_SCAN_SIZE = 200;

	/** 한 그룹에서 비교할 최대 건수. 이보다 많으면 blocking 키가 너무 뭉툭한 것이므로 건너뛴다. */
	private static final int MAX_GROUP_SIZE = 6;

	private static final String SYSTEM_PROMPT = """
			너는 장학금 공고 목록에서 같은 장학금이 중복 등록된 것을 찾아내는 도구다.
			반드시 JSON 배열만 출력한다(설명·코드펜스 금지).

			입력의 각 줄은 "id|제목|기관|유형|신청기간|금액|선발인원|출처" 형식이다.

			출력 형식:
			[{"primaryId":숫자,"duplicateId":숫자,"reason":"같다고 본 근거"}]

			판정 규칙:
			- 같은 기관이 주는 같은 장학금이 두 번 등록된 경우만 중복이다.
			- 다음은 중복이 아니다. 절대 묶지 마라.
			  · 학기·차수가 다른 모집 (1학기 vs 2학기, 1차 vs 2차)
			  · 캠퍼스가 다른 모집 (서울캠퍼스 vs 다빈치캠퍼스)
			  · 신청기간이 겹치지 않는 모집
			  · 대상·금액이 뚜렷하게 다른 모집
			- 확신이 없으면 넣지 마라. 빈 배열 []을 출력해도 된다.
			- primaryId 는 남길 쪽이다. 정보가 더 완전한 쪽(신청기간·금액·선발인원이 채워진 쪽,
			  같으면 출처가 학교 공지인 쪽)을 primaryId 로 둔다.
			- reason 에는 왜 같은 장학금이라고 보았는지 한 문장으로 쓴다.
			""";

	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipMergeCandidateRepository mergeCandidateRepository;
	private final ScholarshipMergeExecutor mergeExecutor;
	private final LlmClient llmClient;
	private final ObjectMapper objectMapper;

	/**
	 * 중복 후보를 탐지해 승인 큐에 올린다. <b>이 단계에서는 아무것도 병합하지 않는다.</b>
	 *
	 * @param limit 검사할 장학금 수 (1 ~ {@value #MAX_SCAN_SIZE})
	 */
	@Transactional
	public MergeDetectionResponse detect(int limit) {
		int size = Math.min(Math.max(limit, 1), MAX_SCAN_SIZE);
		List<Scholarship> targets = scholarshipRepository
				.findByDeletedAtIsNullOrderByIdDesc(PageRequest.of(0, size));

		// 이미 후보로 올라와 있는 장학금은 제외한다. 한 장학금이 여러 쌍에 동시에 올라
		// 병합 순서에 따라 결과가 달라지는 것을 막는다.
		Set<Long> alreadyQueued = new HashSet<>(
				mergeCandidateRepository.findScholarshipIdsByStatus(MergeCandidateStatus.PENDING));

		Map<String, List<Scholarship>> groups = new LinkedHashMap<>();
		for (Scholarship scholarship : targets) {
			if (alreadyQueued.contains(scholarship.getId())) {
				continue;
			}
			String key = ScholarshipTitleBlocker.blockingKey(scholarship.getTitle());
			if (key != null) {
				groups.computeIfAbsent(key, k -> new ArrayList<>()).add(scholarship);
			}
		}

		int groupCount = 0;
		int created = 0;
		int skipped = 0;
		int failed = 0;

		for (var entry : groups.entrySet()) {
			List<Scholarship> group = entry.getValue();
			if (group.size() < 2) {
				continue;
			}
			if (group.size() > MAX_GROUP_SIZE) {
				// blocking 키가 너무 뭉툭해 무관한 공고까지 묶인 상태다. LLM 에 넘기면
				// 잘못된 쌍을 만들 위험이 크고 토큰도 많이 쓴다.
				log.warn("[Dedup] 그룹이 너무 큼 → 건너뜀. key={} size={}", entry.getKey(), group.size());
				continue;
			}
			groupCount++;
			try {
				for (DuplicatePair pair : askLlm(group)) {
					Long primaryId = pair.primaryId();
					Long duplicateId = pair.duplicateId();
					if (!isValidPair(group, primaryId, duplicateId)) {
						continue;
					}
					if (mergeCandidateRepository
							.existsByPrimary_IdAndDuplicate_Id(primaryId, duplicateId)
							|| mergeCandidateRepository
									.existsByPrimary_IdAndDuplicate_Id(duplicateId, primaryId)) {
						skipped++;
						continue;
					}
					mergeCandidateRepository.save(ScholarshipMergeCandidate.builder()
							.primary(find(group, primaryId))
							.duplicate(find(group, duplicateId))
							.reason(pair.reason())
							.build());
					created++;
				}
			} catch (Exception e) {
				// 한 그룹이 실패해도 나머지는 계속 본다.
				log.warn("[Dedup] 그룹 판정 실패 key={} : {}", entry.getKey(), e.getMessage());
				failed++;
			}
		}

		log.info("[Dedup] 검사={} 그룹={} 신규후보={} 중복스킵={} 실패={}",
				targets.size(), groupCount, created, skipped, failed);
		return new MergeDetectionResponse(targets.size(), groupCount, created, skipped, failed);
	}

	/** 승인 대기 목록 조회. */
	@Transactional(readOnly = true)
	public MergeCandidateResponse list(MergeCandidateStatus status, int page, int size) {
		var result = mergeCandidateRepository.findByStatusOrderByIdAsc(
				status, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
		return MergeCandidateResponse.of(result.getContent(), (int) result.getTotalElements());
	}

	/**
	 * 후보를 승인해 병합한다.
	 *
	 * @param reviewer 승인한 관리자. 감사 추적용으로 남긴다
	 */
	@Transactional
	public MergeResultResponse approve(Long candidateId, UUID reviewer) {
		ScholarshipMergeCandidate candidate = mergeCandidateRepository.findById(candidateId)
				.orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT));
		if (!candidate.isPending()) {
			// 이미 처리된 후보를 다시 승인하면 병합이 두 번 일어난다.
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}

		Scholarship primary = candidate.getPrimary();
		Scholarship duplicate = candidate.getDuplicate();
		Long primaryId = primary.getId();
		Long duplicateId = duplicate.getId();
		try {
			Map<String, Integer> moved = mergeExecutor.merge(primary, duplicate);

			/*
			merge() 는 벌크 연산 뒤 영속성 컨텍스트를 비운다(flush + clear). 그래서 위에서 읽어 둔
			candidate 는 이 지점에서 detach 상태이고, 그대로 markMerged() 를 불러도 DB 에 반영되지
			않는다. 실제로 후보가 PENDING 으로 남아 같은 병합을 두 번 승인할 수 있었다.
			다시 읽어 관리 상태로 만든 뒤 기록한다.
			 */
			ScholarshipMergeCandidate reattached = mergeCandidateRepository.findById(candidateId)
					.orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT));
			reattached.markMerged(reviewer, moved.toString());

			return new MergeResultResponse(candidateId, MergeCandidateStatus.MERGED.name(),
					primaryId, duplicateId, moved);
		} catch (Exception e) {
			/*
			실패하면 트랜잭션이 되돌아가므로 후보는 PENDING 으로 남는다. 원인을 DB 에 적어도 같이
			롤백되어 남지 않기 때문에, 상태를 바꾸려는 시도를 하지 않고 로그로만 남긴다.
			PENDING 으로 남는 것이 운영상 옳다 — 아무것도 바뀌지 않았으니 원인을 고쳐 다시 승인하면 된다.
			 */
			log.error("[Dedup] 병합 실패 candidateId={} primary={} duplicate={}. 후보는 PENDING 으로 남는다",
					candidateId, primaryId, duplicateId, e);
			throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
		}
	}

	/** 후보를 반려한다. 같은 쌍이 다음 배치에서 다시 올라오지 않는다. */
	@Transactional
	public MergeResultResponse reject(Long candidateId, UUID reviewer, String note) {
		ScholarshipMergeCandidate candidate = mergeCandidateRepository.findById(candidateId)
				.orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT));
		if (!candidate.isPending()) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		candidate.markRejected(reviewer, note);
		return new MergeResultResponse(candidateId, MergeCandidateStatus.REJECTED.name(),
				candidate.getPrimary().getId(), candidate.getDuplicate().getId(), Map.of());
	}

	// --- LLM 판정 ---

	private List<DuplicatePair> askLlm(List<Scholarship> group) {
		String input = group.stream().map(this::describe).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
		String response = llmClient.chat(LlmChatRequest.of(
				LlmModel.PARSING, SYSTEM_PROMPT, List.of(LlmMessage.user(input))));
		return parse(response);
	}

	private String describe(Scholarship s) {
		return String.join("|",
				String.valueOf(s.getId()),
				nullSafe(s.getTitle()),
				nullSafe(s.getProvider()),
				s.getScholarshipType() == null ? "" : s.getScholarshipType().name(),
				period(s),
				s.getAmount() == null ? "" : String.valueOf(s.getAmount()),
				s.getSelectionCount() == null ? "" : String.valueOf(s.getSelectionCount()),
				nullSafe(s.getPrimarySource()));
	}

	private static String period(Scholarship s) {
		if (s.getApplicationStartAt() == null && s.getApplicationEndAt() == null) {
			return "미확보";
		}
		return date(s.getApplicationStartAt()) + "~" + date(s.getApplicationEndAt());
	}

	private static String date(LocalDateTime value) {
		return value == null ? "" : value.toLocalDate().toString();
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value.replace("|", " ").replaceAll("\\s+", " ").trim();
	}

	private List<DuplicatePair> parse(String rawResponse) {
		if (rawResponse == null || rawResponse.isBlank()) {
			return List.of();
		}
		try {
			String json = rawResponse.strip();
			if (json.startsWith("```")) {
				json = json.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").strip();
			}
			int start = json.indexOf('[');
			int end = json.lastIndexOf(']');
			if (start < 0 || end <= start) {
				return List.of();
			}
			return objectMapper.readValue(json.substring(start, end + 1), new TypeReference<>() {
			});
		} catch (Exception e) {
			log.warn("[Dedup] LLM 응답 파싱 실패: {}", e.getMessage());
			return List.of();
		}
	}

	/**
	 * LLM 이 돌려준 쌍이 실제로 이 그룹 안에 있는 장학금인지 확인한다.
	 * 그룹에 없는 ID 를 만들어내면 무관한 장학금이 병합 후보로 올라간다.
	 */
	private boolean isValidPair(List<Scholarship> group, Long primaryId, Long duplicateId) {
		if (primaryId == null || duplicateId == null || primaryId.equals(duplicateId)) {
			return false;
		}
		boolean bothInGroup = find(group, primaryId) != null && find(group, duplicateId) != null;
		if (!bothInGroup) {
			log.warn("[Dedup] 그룹에 없는 ID 쌍 → 폐기. primary={} duplicate={}", primaryId, duplicateId);
		}
		return bothInGroup;
	}

	private Scholarship find(List<Scholarship> group, Long id) {
		return group.stream().filter(s -> s.getId().equals(id)).findFirst().orElse(null);
	}

	private record DuplicatePair(Long primaryId, Long duplicateId, String reason) {
	}
}
