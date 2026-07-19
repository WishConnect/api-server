package com.wishconnect.domain.scholarship.scheduler;

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
장학금 공공데이터 자동 수집 배치입니다. 매일 정해진 시각(기본 밤 11시 KST)에
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

	@Scheduled(cron = "${scholarship.sync.cron:0 0 23 * * *}", zone = "Asia/Seoul")
	public void syncDaily() {
		log.info("[SyncBatch] 장학금 일일 동기화 시작");
		try {
			long beforeCount = scholarshipRepository.count();
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
			ConditionExtractionResponse extraction = conditionExtractionService.extract();
			log.info("[SyncBatch] 조건 추출 완료 target={} extracted={}",
					extraction.targetCount(), extraction.extractedCount());
		} catch (Exception e) {
			log.warn("[SyncBatch] 조건 추출 실패(동기화 결과에는 영향 없음): {}", e.getMessage());
		}
	}
}
