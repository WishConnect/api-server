package com.wishconnect.domain.application.service;

import com.wishconnect.domain.application.dto.response.ApplicationListItemResponse;
import com.wishconnect.domain.application.dto.response.ApplicationListResponse;
import com.wishconnect.domain.application.dto.response.ProgressResponse;
import com.wishconnect.domain.application.entity.Essay;
import com.wishconnect.domain.application.entity.EssayStatus;
import com.wishconnect.domain.application.repository.EssayAnswerRepository;
import com.wishconnect.domain.application.repository.EssayQuestionRepository;
import com.wishconnect.domain.application.repository.EssayRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자기소개서(지원서) 조회/생성/상세 서비스.
 * Notion API 명세서의 ①·②·③ 엔드포인트를 담당한다.
 * (STEP1 인터뷰·STEP2 답변 관리는 별도 서비스에서 처리)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EssayApplicationService {

	private final EssayRepository essayRepository;
	private final EssayQuestionRepository essayQuestionRepository;
	private final EssayAnswerRepository essayAnswerRepository;

	/**
	 * ① 지원서 목록 조회. status 로 필터링 가능.
	 */
	public ApplicationListResponse getApplications(UUID userId, EssayStatus statusFilter, Pageable pageable) {
		Page<Essay> page = statusFilter != null
				? essayRepository.findByUser_IdAndStatus(userId, statusFilter, pageable)
				: essayRepository.findByUser_Id(userId, pageable);

		List<ApplicationListItemResponse> items = page.getContent().stream()
				.map(this::toListItem)
				.toList();

		return new ApplicationListResponse(items, page.getTotalElements());
	}

	private ApplicationListItemResponse toListItem(Essay essay) {
		int total = (int) essayQuestionRepository.countByEssay_Id(essay.getId());
		int completed = (int) essayAnswerRepository.countByEssayQuestion_Essay_IdAndIsCompletedTrue(essay.getId());
		return new ApplicationListItemResponse(
				essay.getId(),
				essay.getScholarship().getId(),
				essay.getScholarship().getTitle(),
				essay.getStatus(),
				new ProgressResponse(completed, total),
				essay.getLastEditedAt()
		);
	}
}
