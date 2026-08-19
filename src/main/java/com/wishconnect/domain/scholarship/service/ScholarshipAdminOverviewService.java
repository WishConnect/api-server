package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.common.repository.ImageRepository;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.dto.AdminOverviewResponse;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipDetailResponse;
import com.wishconnect.domain.scholarship.dto.AdminRawFailureResponse;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipAnomalyResponse;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipRow;
import com.wishconnect.domain.scholarship.dto.AlwaysOpenScholarshipResponse;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipSourceAggregate;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/*
관리자 화면용 읽기 전용 집계 서비스입니다.

수집 파이프라인을 고쳐도 psql 로 직접 조회해야만 효과를 알 수 있던 문제를 없애는 것이 목적입니다.
배치 실행 이력 테이블이 없어(현재는 로그로만 남습니다) "이번 배치가 몇 건 실패했는가"는 알 수 없고,
기존 테이블을 집계해 "지금 데이터가 어떤 상태인가"를 보여줍니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScholarshipAdminOverviewService {

	/** 관리자 목록 한 번에 보여줄 최대 건수. 눈으로 훑는 화면이라 크게 둘 이유가 없다. */
	private static final int MAX_ROWS = 200;

	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final RawScholarshipRepository rawScholarshipRepository;
	private final ImageRepository imageRepository;
	private final ScholarshipDocumentRepository scholarshipDocumentRepository;
	private final ImageStorageService imageStorageService;

	public AdminOverviewResponse overview() {
		Map<String, Long> posterCountBySource = posterCountBySource();

		List<AdminOverviewResponse.SourceQuality> sourceQuality = scholarshipRepository
				.aggregateQualityBySource()
				.stream()
				.map(aggregate -> toSourceQuality(aggregate, posterCountBySource))
				.toList();

		return new AdminOverviewResponse(
				LocalDateTime.now(),
				rawSummary(),
				scholarshipSummary(),
				sourceQuality);
	}

	public List<AdminScholarshipRow> recent(String source, Integer size) {
		int limit = size == null || size <= 0 ? 50 : Math.min(size, MAX_ROWS);
		Set<Long> posterIds = posterScholarshipIds();

		return scholarshipRepository
				.findRecentForAdmin(StringUtils.hasText(source) ? source : null, PageRequest.of(0, limit))
				.stream()
				.map(scholarship -> toRow(scholarship, posterIds))
				.toList();
	}

	public org.springframework.data.domain.Page<AlwaysOpenScholarshipResponse> alwaysOpen(
			org.springframework.data.domain.Pageable pageable) {
		return scholarshipRepository
				.findByRecruitmentStatusAndDeletedAtIsNullOrderByCreatedAtAsc(
						RecruitmentStatus.ALWAYS_OPEN, pageable)
				.map(s -> new AlwaysOpenScholarshipResponse(s.getId(), s.getTitle(), s.getProvider(),
						s.getCreatedAt(), s.getAlwaysOpenReviewedAt(),
						scholarshipConditionRepository.countByScholarshipId(s.getId()),
						StringUtils.hasText(s.getDetailUrl()) ? s.getDetailUrl() : s.getHomepageUrl()));
	}

	@Transactional
	public void confirmAlwaysOpen(Long scholarshipId) {
		Scholarship scholarship = scholarshipRepository.findById(scholarshipId)
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
		try {
			scholarship.confirmAlwaysOpen();
		} catch (IllegalStateException exception) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
	}

	public Page<AdminScholarshipRow> search(
			String keyword, String source, RecruitmentStatus status, boolean includeDeleted, Pageable pageable) {
		Set<Long> posterIds = posterScholarshipIds();
		return scholarshipRepository.searchForAdmin(
				StringUtils.hasText(keyword) ? keyword.trim() : null,
				StringUtils.hasText(source) ? source.trim() : null,
				status,
				includeDeleted,
				pageable).map(scholarship -> toRow(scholarship, posterIds));
	}

	public AdminScholarshipDetailResponse detail(Long scholarshipId) {
		Scholarship scholarship = scholarshipRepository.findById(scholarshipId)
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND));
		List<AdminScholarshipDetailResponse.RawData> raw = rawScholarshipRepository
				.findAllByScholarshipIdOrderByIdDesc(scholarshipId).stream()
				.map(value -> new AdminScholarshipDetailResponse.RawData(
						value.getId(), value.getSource(), value.getSourceId(), value.getSourceUrl(),
						value.getRawJson(), value.getRawHtml(), value.getParseStatus().name(),
						value.getParseError(), value.getCrawledAt()))
				.toList();
		List<AdminScholarshipDetailResponse.ConditionData> conditions = scholarshipConditionRepository
				.findAllByScholarshipId(scholarshipId).stream()
				.map(value -> new AdminScholarshipDetailResponse.ConditionData(
						value.getId(), value.getConditionType().name(), value.getOperator().name(),
						value.getNecessity().name(), value.getValueInt(), value.getValueIntMax(),
						value.getValueString(), value.isAutoExtracted(), value.getRefs().stream()
								.map(ref -> new AdminScholarshipDetailResponse.RefData(ref.getRefId(), ref.getRefCode()))
								.toList()))
				.toList();
		List<AdminScholarshipDetailResponse.DocumentData> documents = scholarshipDocumentRepository
				.findAllByScholarshipIdOrderByDisplayOrderAsc(scholarshipId).stream()
				.map(value -> new AdminScholarshipDetailResponse.DocumentData(
						value.getId(), value.getName(), value.isEssay(), value.getDisplayOrder(),
						value.getDownloadUrl()))
				.toList();
		List<AdminScholarshipDetailResponse.ImageData> images = imageRepository
				.findAllByEntityTypeAndEntityIdOrderByIdAsc(
						ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarshipId).stream()
				.map(value -> new AdminScholarshipDetailResponse.ImageData(
						value.getId(), value.getImageType(), value.getOriginalName(), value.getContentType(),
						value.getFileSize(), value.getSourceUrl(), imageStorageService.publicUrl(value.getS3Key())))
				.toList();

		return new AdminScholarshipDetailResponse(scholarshipData(scholarship), raw, conditions, documents, images);
	}

	public Page<AdminRawFailureResponse> failures(Pageable pageable) {
		return rawScholarshipRepository.findByParseStatusInOrderByUpdatedAtDesc(
				List.of(ParseStatus.FAILED, ParseStatus.SKIPPED, ParseStatus.IMAGE_ONLY), pageable)
				.map(raw -> new AdminRawFailureResponse(
						raw.getId(), raw.getScholarship() == null ? null : raw.getScholarship().getId(),
						raw.getSource(), raw.getSourceId(), raw.getSourceUrl(), raw.getParseStatus().name(),
						raw.getParseError(), raw.getCrawledAt(), raw.getUpdatedAt()));
	}

	public Page<AdminScholarshipAnomalyResponse> anomalies(Pageable pageable) {
		return scholarshipRepository.findAdminAnomalies(pageable)
				.map(value -> new AdminScholarshipAnomalyResponse(
						value.getId(), value.getTitle(), value.getProvider(), name(value.getRecruitmentStatus()),
						value.getApplicationStartAt(), value.getApplicationEndAt(), value.getPrimarySource(),
						anomalyTypes(value)));
	}

	private List<String> anomalyTypes(Scholarship value) {
		java.util.ArrayList<String> result = new java.util.ArrayList<>();
		if (!StringUtils.hasText(value.getTitle())) result.add("EMPTY_TITLE");
		if (!StringUtils.hasText(value.getProvider())) result.add("MISSING_PROVIDER");
		if (value.getApplicationStartAt() != null && value.getApplicationEndAt() != null
				&& value.getApplicationStartAt().isAfter(value.getApplicationEndAt())) result.add("DATE_REVERSED");
		if (value.getRecruitmentStatus() == RecruitmentStatus.OPEN && value.getApplicationEndAt() != null
				&& value.getApplicationEndAt().isBefore(LocalDateTime.now())) result.add("OPEN_BUT_ENDED");
		if ((value.getRecruitmentStatus() == RecruitmentStatus.OPEN
				|| value.getRecruitmentStatus() == RecruitmentStatus.ALWAYS_OPEN)
				&& !StringUtils.hasText(value.getHomepageUrl()) && !StringUtils.hasText(value.getDetailUrl())) {
			result.add("MISSING_LINK");
		}
		if ((value.getRecruitmentStatus() == RecruitmentStatus.OPEN
				|| value.getRecruitmentStatus() == RecruitmentStatus.ALWAYS_OPEN)
				&& scholarshipConditionRepository.countByScholarshipId(value.getId()) == 0) {
			result.add("MISSING_CONDITION");
		}
		return List.copyOf(result);
	}

	private AdminScholarshipDetailResponse.ScholarshipData scholarshipData(Scholarship value) {
		return new AdminScholarshipDetailResponse.ScholarshipData(
				value.getId(), value.getTitle(), value.getProvider(), value.getSummary(), value.getDescription(),
				name(value.getScholarshipType()), name(value.getRecruitmentStatus()), value.getApplicationStartAt(),
				value.getApplicationEndAt(), value.getSelectionCount(), value.getAmount(), value.isActive(),
				value.isVerified(), value.getPrimarySource(), value.getHomepageUrl(), value.getDetailUrl(),
				name(value.getNoticeKind()), value.isCombined(), value.getSubmissionMethod(),
				name(value.getSubmissionChannel()), value.getSubmissionEvidence(), value.getContact(),
				name(value.getEssayRequirement()), value.getEssayEvidence(), name(value.getInterviewRequirement()),
				value.getInterviewEvidence(), value.getCreatedAt(), value.getUpdatedAt(), value.getDeletedAt());
	}

	private String name(Enum<?> value) {
		return value == null ? null : value.name();
	}

	private AdminOverviewResponse.RawSummary rawSummary() {
		long pending = rawScholarshipRepository.countByParseStatus(ParseStatus.PENDING);
		long parsed = rawScholarshipRepository.countByParseStatus(ParseStatus.PARSED);
		long skipped = rawScholarshipRepository.countByParseStatus(ParseStatus.SKIPPED);
		long imageOnly = rawScholarshipRepository.countByParseStatus(ParseStatus.IMAGE_ONLY);
		long failed = rawScholarshipRepository.countByParseStatus(ParseStatus.FAILED);
		return new AdminOverviewResponse.RawSummary(
				pending + parsed + skipped + imageOnly + failed,
				pending, parsed, skipped, imageOnly, failed);
	}

	private AdminOverviewResponse.ScholarshipSummary scholarshipSummary() {
		return new AdminOverviewResponse.ScholarshipSummary(
				scholarshipRepository.count(),
				scholarshipRepository.countByActiveTrueAndDeletedAtIsNull(),
				scholarshipRepository.countByDeletedAtIsNotNull(),
				scholarshipRepository.countByRecruitmentStatusAndDeletedAtIsNull(RecruitmentStatus.OPEN),
				scholarshipRepository.countByRecruitmentStatusAndDeletedAtIsNull(RecruitmentStatus.UPCOMING),
				scholarshipRepository.countByRecruitmentStatusAndDeletedAtIsNull(RecruitmentStatus.CLOSED),
				scholarshipRepository.countByRecruitmentStatusAndDeletedAtIsNull(RecruitmentStatus.ALWAYS_OPEN),
				scholarshipRepository.countByCreatedAtGreaterThanEqual(LocalDate.now().atStartOfDay()),
				scholarshipRepository.findLastSyncedAt());
	}

	/** 포스터가 붙은 장학금 id 집합. Image 는 엔티티 연관이 없어 조인 대신 집합 대조로 처리한다. */
	private Set<Long> posterScholarshipIds() {
		return new HashSet<>(imageRepository
				.findEntityIdsByEntityType(ImageStorageService.ENTITY_TYPE_SCHOLARSHIP));
	}

	/**
	 * 출처별 포스터 보유 건수. 포스터가 하나도 없으면 IN () 이 되므로 조회를 건너뛴다.
	 *
	 * <p>수기 등록분은 {@code primarySource} 가 null 이라 키가 null 로 들어온다.
	 * {@code Map.of()} 는 null 키 조회에서 NPE 를 던지므로 반드시 HashMap 을 쓴다.
	 */
	private Map<String, Long> posterCountBySource() {
		Set<Long> posterIds = posterScholarshipIds();
		if (posterIds.isEmpty()) {
			return new HashMap<>();
		}
		Map<String, Long> result = new HashMap<>();
		for (Object[] row : scholarshipRepository.countBySourceForIds(posterIds)) {
			result.put(row[0] == null ? null : (String) row[0], (Long) row[1]);
		}
		return result;
	}

	private AdminOverviewResponse.SourceQuality toSourceQuality(
			ScholarshipSourceAggregate aggregate, Map<String, Long> posterCountBySource) {
		return new AdminOverviewResponse.SourceQuality(
				aggregate.getSource() == null ? "MANUAL" : aggregate.getSource(),
				aggregate.getTotal(),
				aggregate.getWithSummary(),
				aggregate.getWithAmount(),
				aggregate.getWithHomepageUrl(),
				posterCountBySource.getOrDefault(aggregate.getSource(), 0L));
	}

	private AdminScholarshipRow toRow(Scholarship scholarship, Set<Long> posterIds) {
		return new AdminScholarshipRow(
				scholarship.getId(),
				scholarship.getTitle(),
				scholarship.getProvider(),
				scholarship.getPrimarySource(),
				scholarship.getRecruitmentStatus() == null ? null : scholarship.getRecruitmentStatus().name(),
				scholarship.getApplicationEndAt(),
				scholarship.getCreatedAt(),
				StringUtils.hasText(scholarship.getSummary()),
				scholarship.getAmount() != null,
				StringUtils.hasText(scholarship.getHomepageUrl()),
				posterIds.contains(scholarship.getId()),
				scholarship.isDeleted());
	}
}
