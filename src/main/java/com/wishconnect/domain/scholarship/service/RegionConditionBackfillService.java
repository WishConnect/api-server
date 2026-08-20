package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.collector.NoticeConditionExtractor;
import com.wishconnect.domain.scholarship.dto.RegionConditionBackfillResponse;
import com.wishconnect.domain.scholarship.dto.RegionConditionBackfillResponse.Sample;
import com.wishconnect.domain.scholarship.entity.ConditionNecessity;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionRef;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.util.ConditionLabelExtractor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 본문을 근거로 <b>거주 요건 조건</b>을 채운다.
 *
 * <p>추천 관문이 알아야 하는 건 하나다 — "이 공고에 거주 요건이 있는가, 어디인가".
 * 그건 본문에 적혀 있는데, 지금까지 일부 공고에만 조건으로 저장돼 있었다. 조건이 없는 공고는
 * 막을 근거가 없어 서울 사는 사용자에게 울산·목포 장학금이 그대로 나갔다.
 *
 * <p><b>제목으로 추론하지 않는다.</b> 한동안 제목에 지역명이 있으면 지역 한정으로 봤는데,
 * 그러면 {@code "서울장학재단 전국 대학생 장학금"} 같은 전국 공고가 서울 밖 사용자에게서 사라진다.
 * 제목은 근거가 아니라 힌트다. 근거는 본문에 있고, 본문을 읽어 <b>조건으로 저장</b>하면
 * 판단이 추론에서 사실로 바뀐다 — 근거 문장이 남으니 관리자가 검증하고 고칠 수도 있다.
 *
 * <p>판별은 {@link NoticeConditionExtractor} 의 기존 규칙을 그대로 쓴다. 지역명 단독으로는 잡지 않고
 * <b>{@code 거주}·{@code 출신}·{@code 소재}</b> 가 가까이 붙어야 잡는다. 그래서 기관 이름에 들어간
 * 지역명("서울장학재단")은 걸리지 않는다.
 *
 * <p>어느 지역인지 해석하지 못하면 <b>아무것도 만들지 않는다.</b> "관내에 주소를 두고" 처럼
 * 지역을 특정할 수 없는 문구로 조건을 만들면, 판정 불가 조건이 하나 늘 뿐인데 그 조건이 있다는
 * 이유로 다른 경로까지 막힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegionConditionBackfillService {

	/** 확인용 표본 상한. 실제 저장 모드에서는 로그와 응답이 지나치게 길어지지 않게 자른다. */
	private static final int SAMPLE_LIMIT = 50;

	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final ConditionLabelExtractor labelExtractor;
	private final ConditionRefResolver refResolver;

	/**
	 * @param limit  한 번에 검사할 공고 수
	 * @param dryRun {@code true} 면 저장하지 않고 무엇이 채워질지만 돌려준다
	 */
	@Transactional
	public RegionConditionBackfillResponse backfill(int limit, boolean dryRun) {
		List<Scholarship> targets = scholarshipRepository.findWithoutConditionType(
				ConditionType.REGION_RESIDENCY, PageRequest.of(0, Math.max(1, Math.min(limit, 2000))));

		int matched = 0;
		int filled = 0;
		int unresolved = 0;
		List<Sample> samples = new ArrayList<>();

		for (Scholarship scholarship : targets) {
			String evidence = findEvidence(scholarship);
			if (evidence == null) {
				continue;
			}
			matched++;

			List<String> labels = labelExtractor.extract(ConditionType.REGION_RESIDENCY, evidence);
			Set<ConditionRef> refs = refResolver.resolve(ConditionType.REGION_RESIDENCY, labels);
			if (refs.isEmpty()) {
				// 어디인지 모르는 채로 조건을 만들면 판정 불가 조건만 늘어난다.
				unresolved++;
				if (samples.size() < SAMPLE_LIMIT) {
					samples.add(new Sample(scholarship.getId(), scholarship.getTitle(), evidence, List.of()));
				}
				continue;
			}

			filled++;
			if (samples.size() < SAMPLE_LIMIT) {
				samples.add(new Sample(scholarship.getId(), scholarship.getTitle(), evidence, labels));
			}
			if (dryRun) {
				continue;
			}
			ScholarshipCondition condition = ScholarshipCondition.builder()
					.scholarship(scholarship)
					.conditionType(ConditionType.REGION_RESIDENCY)
					.operator(ConditionOperator.EQ)
					.necessity(ConditionNecessity.REQUIRED)
					.valueString(evidence)
					.autoExtracted(true)
					.build();
			condition.applyRefs(refs);
			scholarshipConditionRepository.save(condition);
		}

		log.info("[RegionBackfill] dryRun={} 검사={} 근거발견={} 채움={} 해석실패={}",
				dryRun, targets.size(), matched, filled, unresolved);
		return new RegionConditionBackfillResponse(
				dryRun, targets.size(), matched, filled, unresolved, List.copyOf(samples));
	}

	/**
	 * 본문에서 거주 요건 문장을 찾는다. 없으면 null.
	 *
	 * <p>{@code summary} 를 먼저 본다. 요약은 자격 요건이 압축돼 있어 잡음이 적다.
	 * 제목은 보지 않는다 — 근거가 아니라 힌트라서, 제목만으로 판단하면 기관 이름에 들어간
	 * 지역명에 걸린다.
	 */
	private String findEvidence(Scholarship scholarship) {
		for (String text : new String[] {scholarship.getSummary(), scholarship.getDescription()}) {
			if (!StringUtils.hasText(text)) {
				continue;
			}
			String found = NoticeConditionExtractor.extract(text).stream()
					.filter(extracted -> extracted.type() == ConditionType.REGION_RESIDENCY)
					.map(NoticeConditionExtractor.Extracted::snippet)
					.findFirst()
					.orElse(null);
			if (found != null) {
				return found;
			}
		}
		return null;
	}
}
