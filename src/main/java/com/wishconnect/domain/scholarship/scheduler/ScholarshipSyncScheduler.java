package com.wishconnect.domain.scholarship.scheduler;

import com.wishconnect.domain.scholarship.collector.DedicatedNoticeCollectors;
import com.wishconnect.domain.scholarship.collector.UnivNoticeCollector;
import com.wishconnect.domain.scholarship.dto.CollectResultResponse;
import com.wishconnect.domain.scholarship.dto.ConditionExtractionResponse;
import com.wishconnect.domain.scholarship.dto.EnrichmentResult;
import com.wishconnect.domain.scholarship.dto.MergeDetectionResponse;
import com.wishconnect.domain.scholarship.dto.NoticeParsingResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipSyncResponse;
import com.wishconnect.domain.scholarship.service.ConditionExtractionService;
import com.wishconnect.domain.scholarship.service.ScholarshipEnrichmentService;
import com.wishconnect.domain.scholarship.service.ScholarshipDedupService;
import com.wishconnect.domain.scholarship.service.UnivNoticeLlmParsingService;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.service.ScholarshipSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
장학금 공공데이터 자동 수집 배치입니다. 매일 정해진 시각(기본 오전 11시 KST)에
동기화를 실행해 최신 공고를 반영하고, 이어서 LLM 조건 구조화 추출과
상세페이지·첨부·포스터 자동 보완을 시도합니다.
- 시각 변경: scholarship.sync.cron (스프링 cron 6필드)
- 배치 비활성화: scholarship.sync.scheduled=false (로컬에서 외부 API 호출을 원치 않을 때)
- 추출 실패(LLM 키 미설정 등)는 수집 결과에 영향을 주지 않도록 분리 처리한다.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scholarship.sync.scheduled", havingValue = "true", matchIfMissing = true)
public class ScholarshipSyncScheduler {

	private final ScholarshipSyncService scholarshipSyncService;
	private final ConditionExtractionService conditionExtractionService;
	private final ScholarshipRepository scholarshipRepository;
	private final UnivNoticeCollector univNoticeCollector;
	private final DedicatedNoticeCollectors dedicatedNoticeCollectors;
	private final ScholarshipEnrichmentService scholarshipEnrichmentService;
	private final UnivNoticeLlmParsingService univNoticeLlmParsingService;
	private final ScholarshipDedupService scholarshipDedupService;

	/**
	 * 한 배치에서 보완할 최대 건수.
	 *
	 * <p>외부 검색·크롤링이라 건당 수 초가 걸린다(요청 간 지연 포함). 50건이면 2~5분쯤이고,
	 * 검색 API 쿼터도 인사이트 수집과 나눠 쓰므로 한 번에 몰지 않는다.
	 * 못 채운 건은 다음 날 배치가 이어서 가져간다.
	 */
	@Value("${scholarship.enrich.batch-limit:50}")
	private int enrichBatchLimit;

	/**
	 * 하루에 LLM 으로 정제할 공고 수 상한.
	 *
	 * <p>크레딧이 자동으로 나가는 유일한 단계라 상한을 둔다. 평소 신규 공고는 하루 수십 건이고,
	 * 못 채운 건 PENDING 으로 남아 다음 날 이어서 처리된다.
	 */
	@Value("${scholarship.parse.batch-limit:60}")
	private int parseBatchLimit;

	/** 중복 후보 탐지 상한. 그룹당 LLM 1회를 쓰므로 함께 제한한다. */
	@Value("${scholarship.merge.batch-limit:30}")
	private int mergeDetectBatchLimit;

	@Scheduled(cron = "${scholarship.sync.cron:0 0 11 * * *}", zone = "Asia/Seoul")
	public void syncDaily() {
		log.info("[SyncBatch] 장학금 일일 동기화 시작");
		try {
			int closedCount = scholarshipSyncService.closeExpired();
			if (closedCount > 0) {
				log.info("[SyncBatch] 마감 지난 공고 {}건 CLOSED 처리", closedCount);
			}
			long beforeCount = scholarshipRepository.count();
            //한국장학재단에서 받아오는 부분
			ScholarshipSyncResponse result = scholarshipSyncService.sync();
			long newCount = scholarshipRepository.count() - beforeCount;
			log.info("[SyncBatch] 동기화 완료 fetched={} saved={} failed={} 신규정제={}",
					result.fetchedCount(), result.savedCount(), result.failedCount(), Math.max(newCount, 0));
			if (result.fetchedCount() == 0) {
				log.info("[SyncBatch] 수집된 공고가 없습니다 (외부 API 응답 0건)");
			} else if (newCount <= 0) {
				log.info("[SyncBatch] 새로운 공고가 없습니다 (기존 공고 갱신만 수행됨)");
			}
		} catch (Exception e) {
			log.error("[SyncBatch] 동기화 실패", e);
			return;
		}
		try {
			for (CollectResultResponse univ : univNoticeCollector.collectAll(1)) {
				log.info("[SyncBatch] 대학 공지 수집 {} fetched={} saved={}",
						univ.source(), univ.fetchedCount(), univ.savedCount());
			}
		} catch (Exception e) {
			log.warn("[SyncBatch] 대학 공지 수집 실패(다른 스텝에 영향 없음): {}", e.getMessage());
		}
		try {
			// 게시판 구조가 공통 규칙으로 묶이지 않아 대학별 클래스로 처리하는 곳들.
			// 레지스트리 안에서 대학 단위로 예외를 삼키므로 한 곳이 실패해도 나머지는 수집된다.
			for (CollectResultResponse univ : dedicatedNoticeCollectors.collectAll(1)) {
				log.info("[SyncBatch] 대학 공지 수집(전용) {} fetched={} saved={}",
						univ.source(), univ.fetchedCount(), univ.savedCount());
			}
		} catch (Exception e) {
			log.warn("[SyncBatch] 전용 수집기 실행 실패(다른 스텝에 영향 없음): {}", e.getMessage());
		}
		try {
			// 수집 바로 다음에 온다. 수집기는 raw_html 만 저장하므로, 이 단계가 없으면
			// 새 공고가 PENDING 인 채 쌓이기만 하고 사용자에게는 아무것도 보이지 않는다.
			NoticeParsingResponse parsing = univNoticeLlmParsingService.parse(parseBatchLimit, false, false);
            //LLM으로 대학공지 정제하는 부분
			log.info("[SyncBatch] LLM 파싱 완료 target={} parsed={} skipped={} failed={}",
					parsing.targetCount(), parsing.parsedCount(), parsing.skippedCount(),
					parsing.failedCount());
		} catch (Exception e) {
			log.warn("[SyncBatch] LLM 파싱 실패(다른 스텝에 영향 없음): {}", e.getMessage());
		}
		try {
			// 파싱이 끝난 뒤라야 새로 들어온 공고까지 중복 검사 대상이 된다.
			// 병합하지는 않는다 — 승인 큐에 올리기만 하고 사람이 확인한다.
			MergeDetectionResponse merge = scholarshipDedupService.detect(mergeDetectBatchLimit);
			log.info("[SyncBatch] 중복 후보 탐지 완료 검사={} 그룹={} 신규후보={} 실패={}",
					merge.scannedCount(), merge.groupCount(), merge.candidateCount(),
					merge.failedCount());
		} catch (Exception e) {
			log.warn("[SyncBatch] 중복 후보 탐지 실패(다른 스텝에 영향 없음): {}", e.getMessage());
		}
		try {
			ConditionExtractionResponse extraction = conditionExtractionService.extract();
			log.info("[SyncBatch] 조건 추출 완료 target={} extracted={}",
					extraction.targetCount(), extraction.extractedCount());
		} catch (Exception e) {
			log.warn("[SyncBatch] 조건 추출 실패(동기화 결과에는 영향 없음): {}", e.getMessage());
		}
		try {
			// 수집 직후에 돌려야 새로 들어온 공고가 그날 바로 상세 URL·첨부·포스터를 갖는다.
			// 공공데이터에 이 세 가지가 아예 없어서 이 단계가 없으면 영영 비어 있다.
			EnrichmentResult enrichment = scholarshipEnrichmentService.enrich(enrichBatchLimit);
			log.info("[SyncBatch] 자동 보완 완료 target={} 상세URL={} 이미지={} 첨부={} 건너뜀={}",
					enrichment.targetCount(), enrichment.detailUrlFound(), enrichment.imageSaved(),
					enrichment.documentLinked(), enrichment.skippedCount());
		} catch (Exception e) {
			log.warn("[SyncBatch] 자동 보완 실패(다른 스텝에 영향 없음): {}", e.getMessage());
		}
	}
}
