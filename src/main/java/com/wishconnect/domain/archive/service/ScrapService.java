package com.wishconnect.domain.archive.service;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.Scrap;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.entity.ScholarshipEventType;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.domain.scholarship.service.ScholarshipEventService;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
장학금 스크랩(아카이빙) 서비스입니다. 상세 화면의 isScrapped와
추천 강화(Phase 2)의 행동 신호 데이터가 여기서 쌓입니다.
 */
@Service
@RequiredArgsConstructor
public class ScrapService {

	private final ScrapRepository scrapRepository;
	private final ScholarshipRepository scholarshipRepository;
	private final UserRepository userRepository;
	// 추천 품질 측정용 행동 기록. 저장 실패가 스크랩을 실패시키지는 않는다.
	private final ScholarshipEventService scholarshipEventService;

	@Transactional
	public void scrap(UUID userId, Long scholarshipId) {
		Scholarship scholarship = scholarshipRepository.findById(scholarshipId)
				.filter(found -> found.getDeletedAt() == null)
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
		if (scrapRepository.existsByUserIdAndScholarshipId(userId, scholarshipId)) {
			throw new CustomException(ErrorCode.ALREADY_SCRAPPED);
		}
		scrapRepository.save(Scrap.builder()
				.user(userRepository.getReferenceById(userId))
				.scholarship(scholarship)
				.build());
		scholarshipEventService.record(userId, scholarshipId, ScholarshipEventType.SCRAP);
	}

	@Transactional
	public void unscrap(UUID userId, Long scholarshipId) {
		Scrap scrap = scrapRepository.findByUserIdAndScholarshipId(userId, scholarshipId)
				.orElseThrow(() -> new CustomException(ErrorCode.SCRAP_NOT_FOUND));
		scrapRepository.delete(scrap);
	}
}
