package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualFullRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualFullResponse;
import com.wishconnect.global.exception.CustomException;
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
		return response(aggregateStore.create(request), request.imageSourceUrl(), false);
	}

	public ScholarshipManualFullResponse createFromRaw(Long rawId, ScholarshipManualFullRequest request) {
		return response(aggregateStore.createFromRaw(rawId, request), request.imageSourceUrl(), false);
	}

	public ScholarshipManualFullResponse update(Long scholarshipId, ScholarshipManualFullRequest request) {
		return response(aggregateStore.update(scholarshipId, request), request.imageSourceUrl(), true);
	}

	private ScholarshipManualFullResponse response(ScholarshipManualAggregateStore.SavedAggregate saved,
			String imageSourceUrl, boolean replace) {
		boolean imageSaved = false;
		String imageError = null;
		if (StringUtils.hasText(imageSourceUrl)) {
			// 이미지 실패는 장학금 저장을 되돌리지 않는다. 대신 원인 문구를 응답에 실어 화면이 알릴 수 있게 한다.
			try {
				imageStorageService.replaceFromUrl(imageSourceUrl, replace ? "scholarships/admin" : "scholarships/manual",
						ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, saved.scholarshipId(), saved.title());
				imageSaved = true;
			} catch (CustomException e) {
				imageError = e.getErrorCode().getMessage();
				log.warn("[Scholarship] 관리자 이미지 저장 실패 (scholarshipId={}, reason={})",
						saved.scholarshipId(), e.getErrorCode());
			}
		}
		return new ScholarshipManualFullResponse(
				saved.scholarshipId(), saved.rawScholarshipId(), saved.conditionCount(),
				saved.conditionRefCount(), saved.documentCount(), imageSaved, imageError);
	}
}
