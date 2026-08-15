package com.wishconnect.domain.scholarship.scheduler;

import com.wishconnect.domain.scholarship.collector.DedicatedNoticeCollectors;
import com.wishconnect.domain.scholarship.collector.UnivNoticeCollector;
import com.wishconnect.domain.scholarship.dto.CollectResultResponse;
import com.wishconnect.domain.scholarship.dto.ConditionExtractionResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipSyncResponse;
import com.wishconnect.domain.scholarship.service.ConditionExtractionService;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.service.ScholarshipSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
장학금 공공데이터 자동 수집 배치입니다. 매일 정해진 시각(기본 오전 11시 KST)에
동기화를 실행해 최신 공고를 반영하고, 이어서 LLM 조건 구조화 추출을 시도합니다.
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
			ConditionExtractionResponse extraction = conditionExtractionService.extract();
			log.info("[SyncBatch] 조건 추출 완료 target={} extracted={}",
					extraction.targetCount(), extraction.extractedCount());
		} catch (Exception e) {
			log.warn("[SyncBatch] 조건 추출 실패(동기화 결과에는 영향 없음): {}", e.getMessage());
		}
	}
}
