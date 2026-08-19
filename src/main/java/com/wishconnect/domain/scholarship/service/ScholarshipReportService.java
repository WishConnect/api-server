package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.ReportResolveRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipReportRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipReportResponse;
import com.wishconnect.domain.scholarship.entity.ReportReason;
import com.wishconnect.domain.scholarship.entity.ReportStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipReport;
import com.wishconnect.domain.scholarship.repository.ScholarshipReportRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장학금 오등록 신고 접수와 관리자 처리.
 *
 * <p>수집 파이프라인이 놓친 오류를 실사용자가 되먹여 주는 창구다.
 * 접수만 받고 끝나면 의미가 없어서, 관리자 목록 조회와 처리 상태 변경까지 함께 둔다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScholarshipReportService {

	private final ScholarshipReportRepository scholarshipReportRepository;
	private final ScholarshipRepository scholarshipRepository;
	private final UserRepository userRepository;

	/** 사용자 신고 접수. 같은 장학금에 미처리 신고가 남아 있으면 중복 접수를 막는다. */
	@Transactional
	public ScholarshipReportResponse report(
			UUID userId, Long scholarshipId, ScholarshipReportRequest request) {

		Scholarship scholarship = scholarshipRepository.findById(scholarshipId)
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
		if (scholarship.isDeleted()) {
			throw new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND);
		}
		if (scholarshipReportRepository.existsByScholarship_IdAndUser_IdAndStatus(
				scholarshipId, userId, ReportStatus.PENDING)) {
			throw new CustomException(ErrorCode.REPORT_ALREADY_EXISTS);
		}

		User user = userRepository.findById(userId)
				.orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
		// 화면이 체크박스라 같은 값이 두 번 실려 올 수 있다. 저장 전에 접어 둔다.
		Set<ReportReason> reasons = new LinkedHashSet<>(request.reasons());
		ScholarshipReport report = scholarshipReportRepository.save(
				ScholarshipReport.create(scholarship, user, reasons, request.detail()));

		log.info("[Scholarship] 오등록 신고 접수 (reportId={}, scholarshipId={}, reasons={})",
				report.getId(), scholarshipId, reasons);
		return ScholarshipReportResponse.from(report);
	}

	/** 관리자 신고 목록. status 가 null 이면 전체를 최신순으로 준다. */
	@Transactional(readOnly = true)
	public Page<ScholarshipReportResponse> findAll(ReportStatus status, Pageable pageable) {
		return findAll(status, null, null, pageable);
	}

	@Transactional(readOnly = true)
	public Page<ScholarshipReportResponse> findAll(ReportStatus status, ReportReason reason,
			String keyword, Pageable pageable) {
		Specification<ScholarshipReport> spec = (root, query, cb) -> {
			java.util.ArrayList<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
			if (status != null) predicates.add(cb.equal(root.get("status"), status));
			if (reason != null) predicates.add(cb.isMember(reason, root.get("reasons")));
			if (keyword != null && !keyword.isBlank()) {
				String like = "%" + keyword.trim().toLowerCase() + "%";
				var scholarship = root.join("scholarship");
				predicates.add(cb.or(cb.like(cb.lower(scholarship.get("title")), like),
						cb.like(cb.lower(scholarship.get("provider")), like),
						cb.like(cb.lower(root.get("detail")), like)));
			}
			return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
		};
		Page<ScholarshipReport> reports = scholarshipReportRepository.findAll(spec, pageable);
		return reports.map(ScholarshipReportResponse::from);
	}

	/** 관리자 처리. 데이터 수정 자체는 수기 수정 API 로 하고, 여기서는 신고 상태만 닫는다. */
	@Transactional
	public ScholarshipReportResponse resolve(Long reportId, ReportResolveRequest request) {
		ScholarshipReport report = scholarshipReportRepository.findById(reportId)
				.orElseThrow(() -> new CustomException(ErrorCode.REPORT_NOT_FOUND));
		report.resolve(request.status(), request.adminNote());
		log.info("[Scholarship] 신고 처리 (reportId={}, status={})", reportId, request.status());
		return ScholarshipReportResponse.from(report);
	}

	/** 내가 낸 신고 목록. 처리 결과를 확인할 수 있어야 신고가 유실됐다는 인상을 주지 않는다. */
	@Transactional(readOnly = true)
	public List<ScholarshipReportResponse> findMine(UUID userId, Pageable pageable) {
		return scholarshipReportRepository.findAllByUser_IdOrderByIdDesc(userId, pageable)
				.stream()
				.map(ScholarshipReportResponse::from)
				.toList();
	}
}
