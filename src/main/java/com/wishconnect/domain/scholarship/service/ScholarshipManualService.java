package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.ScholarshipManualRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualResponse;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장학금 수기 등록·수정·삭제(운영용).
 *
 * <p>수집이 공공데이터 API 와 크롤링에 의존해 누락과 오류가 남는다.
 * 주최사가 직접 알려준 공고나 신고로 확인된 오류를 사람이 바로 반영할 창구가 필요하다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScholarshipManualService {

	private final ScholarshipRepository scholarshipRepository;

	/**
	 * 수기 등록. dedupKey 는 공공데이터 키와 겹치지 않도록 {@code MANUAL:} 접두사를 붙인 UUID 로 만든다.
	 * 겹치면 다음 동기화가 이 행을 API 값으로 덮어써 수기 입력이 사라진다.
	 */
	@Transactional
	public ScholarshipManualResponse create(ScholarshipManualRequest.Create request) {
		Scholarship scholarship = Scholarship.createManual(
				request.title().trim(),
				request.provider(),
				request.summary(),
				request.description(),
				request.scholarshipType() == null ? ScholarshipType.EXTERNAL : request.scholarshipType(),
				request.applicationStartAt(),
				request.applicationEndAt(),
				request.selectionCount(),
				request.amount(),
				request.homepageUrl(),
				Scholarship.MANUAL_SOURCE + ":" + UUID.randomUUID()
		);
		validatePeriod(scholarship.getApplicationStartAt(), scholarship.getApplicationEndAt());
		Scholarship saved = scholarshipRepository.save(scholarship);
		log.info("[Scholarship] 수기 등록 (scholarshipId={}, title={})", saved.getId(), saved.getTitle());
		return ScholarshipManualResponse.from(saved);
	}

	/** 관리자 직접 수정. 보낸 필드만 반영한다(수집분·수기분 모두 대상). */
	@Transactional
	public ScholarshipManualResponse update(Long scholarshipId, ScholarshipManualRequest request) {
		Scholarship scholarship = getScholarship(scholarshipId);
		validatePeriod(
				request.applicationStartAt() == null
						? scholarship.getApplicationStartAt() : request.applicationStartAt(),
				request.applicationEndAt() == null
						? scholarship.getApplicationEndAt() : request.applicationEndAt());

		scholarship.updateByAdmin(
				request.title() == null ? null : request.title().trim(),
				request.provider(),
				request.summary(),
				request.description(),
				request.scholarshipType(),
				request.applicationStartAt(),
				request.applicationEndAt(),
				request.selectionCount(),
				request.amount(),
				request.homepageUrl()
		);
		if (request.recruitmentStatus() != null) {
			scholarship.updateRecruitmentStatusByAdmin(request.recruitmentStatus());
		}
		log.info("[Scholarship] 관리자 수정 (scholarshipId={})", scholarshipId);
		return ScholarshipManualResponse.from(scholarship);
	}

	/** 오등록으로 확인된 장학금을 목록에서 내린다(soft delete). */
	@Transactional
	public void delete(Long scholarshipId) {
		Scholarship scholarship = getScholarship(scholarshipId);
		scholarship.softDelete();
		log.info("[Scholarship] 관리자 삭제 (scholarshipId={})", scholarshipId);
	}

	private Scholarship getScholarship(Long scholarshipId) {
		Scholarship scholarship = scholarshipRepository.findById(scholarshipId)
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
		if (scholarship.isDeleted()) {
			throw new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND);
		}
		return scholarship;
	}

	/** 마감이 시작보다 앞서면 모집 상태 계산이 뒤틀리므로 입력 단계에서 막는다. */
	private void validatePeriod(java.time.LocalDateTime startAt, java.time.LocalDateTime endAt) {
		if (startAt != null && endAt != null && endAt.isBefore(startAt)) {
			throw new CustomException(ErrorCode.INVALID_APPLICATION_PERIOD);
		}
	}
}
