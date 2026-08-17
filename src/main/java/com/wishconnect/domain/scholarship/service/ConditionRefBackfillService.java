package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.ConditionRefBackfillResponse;
import com.wishconnect.domain.scholarship.entity.ConditionRef;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.util.ConditionLabelExtractor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이미 저장된 <b>공공데이터 조건</b>에 마스터 참조를 채운다.
 *
 * <p>대학공지는 재파싱하면 LLM 이 참조까지 채워 주지만, 공공데이터 890건은 재파싱 경로가 없다.
 * 그대로 두면 참조가 비어 있어 {@code ConditionMatcher} 가 전부 판정 불가로 넘긴다 —
 * Phase 3 에서 지역·자격·전공 매칭을 켜도 <b>공공데이터 장학금만 아무 판정도 안 되는</b> 상태가 된다.
 *
 * <p>LLM 을 쓰지 않는다. 공공데이터는 한국장학재단이 이미 필드를 나눠서 주기 때문에 규칙으로 충분하고,
 * 규칙은 같은 입력에 같은 답을 낸다 — 언제든 다시 돌려도 결과가 흔들리지 않는다.
 *
 * <p>참조가 이미 있는 조건은 건너뛴다. LLM 이 본문을 읽고 채운 값을 규칙 스캔으로 덮어쓰면
 * 더 나쁜 값으로 되돌린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConditionRefBackfillService {

	/** 대조할 마스터가 있는 유형만. 나머지는 수치나 원문으로 판정한다. */
	private static final List<ConditionType> REF_ABLE_TYPES = List.of(
			ConditionType.REGION_RESIDENCY,
			ConditionType.SPECIFIC_QUALIFICATION,
			ConditionType.MAJOR_FIELD,
			ConditionType.FINANCIAL_AID_TYPE);

	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final ConditionLabelExtractor labelExtractor;
	private final ConditionRefResolver refResolver;

	/**
	 * @param limit 한 번에 처리할 조건 수. 지역 마스터를 전건 훑으므로 상한을 둔다.
	 */
	@Transactional
	public ConditionRefBackfillResponse backfill(int limit) {
		List<ScholarshipCondition> targets = scholarshipConditionRepository
				.findRefBackfillTargets(REF_ABLE_TYPES, PageRequest.of(0, limit));
		if (targets.isEmpty()) {
			return new ConditionRefBackfillResponse(0, 0, 0, Map.of());
		}

		int filled = 0;
		int refCount = 0;
		Map<String, Integer> byType = new LinkedHashMap<>();
		for (ScholarshipCondition condition : targets) {
			List<String> labels = labelExtractor.extract(condition.getConditionType(), condition.getValueString());
			Set<ConditionRef> refs = refResolver.resolve(condition.getConditionType(), labels);
			if (refs.isEmpty()) {
				continue;
			}
			condition.applyRefs(refs);
			filled++;
			refCount += refs.size();
			byType.merge(condition.getConditionType().name(), 1, Integer::sum);
		}

		log.info("[ConditionRefBackfill] 대상={} 채움={} 참조={} 유형별={}",
				targets.size(), filled, refCount, byType);
		return new ConditionRefBackfillResponse(targets.size(), filled, refCount, byType);
	}
}
