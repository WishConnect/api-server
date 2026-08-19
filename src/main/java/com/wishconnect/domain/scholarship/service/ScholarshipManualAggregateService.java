package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualFullRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualFullResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 통합 수기 등록을 조율한다. DB 트랜잭션 종료 후 외부 이미지를 받아 장시간 DB 잠금을 피한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScholarshipManualAggregateService {

	private final ScholarshipManualAggregateStore aggregateStore;
	private final ImageStorageService imageStorageService;

	public ScholarshipManualFullResponse create(ScholarshipManualFullRequest request) {
		ScholarshipManualAggregateStore.SavedAggregate saved = aggregateStore.create(request);
		boolean imageSaved = false;
		if (StringUtils.hasText(request.imageSourceUrl())) {
			imageSaved = imageStorageService.storeFromUrl(
					request.imageSourceUrl(),
					"scholarships/manual",
					ImageStorageService.ENTITY_TYPE_SCHOLARSHIP,
					saved.scholarshipId(),
					saved.title()) != null;
			if (!imageSaved) {
				log.warn("[Scholarship] 수기 등록 이미지 저장 실패 (scholarshipId={})", saved.scholarshipId());
			}
		}
		return new ScholarshipManualFullResponse(
				saved.scholarshipId(), saved.rawScholarshipId(), saved.conditionCount(),
				saved.conditionRefCount(), saved.documentCount(), imageSaved);
	}
}
