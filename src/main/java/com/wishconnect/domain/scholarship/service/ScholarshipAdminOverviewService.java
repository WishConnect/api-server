package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.common.entity.Image;
import com.wishconnect.domain.common.repository.ImageRepository;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.dto.AdminImageRowResponse;
import com.wishconnect.domain.scholarship.dto.AdminIntakeRowResponse;
import com.wishconnect.domain.scholarship.dto.AdminOverviewResponse;
import com.wishconnect.domain.scholarship.dto.AdminRawDetailResponse;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipDetailResponse;
import com.wishconnect.domain.scholarship.dto.AdminRawFailureResponse;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipAnomalyResponse;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipRow;
import com.wishconnect.domain.scholarship.dto.AlwaysOpenScholarshipResponse;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
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
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;
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
	private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

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
		return scholarshipRepository.findAll(scholarshipSpec(keyword, source, status, includeDeleted), pageable)
				.map(scholarship -> toRow(scholarship, posterIds));
	}

	public Page<AdminIntakeRowResponse> intake(LocalDate date, String keyword, String source,
			ParseStatus status, Pageable pageable) {
		LocalDate target = date == null ? LocalDate.now(KOREA_ZONE).minusDays(1) : date;
		Specification<RawScholarship> spec = (root, query, cb) -> {
			List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
			predicates.add(cb.greaterThanOrEqualTo(root.get("crawledAt"), target.atStartOfDay()));
			predicates.add(cb.lessThan(root.get("crawledAt"), target.plusDays(1).atStartOfDay()));
			if (StringUtils.hasText(source)) predicates.add(cb.equal(root.get("source"), source.trim()));
			if (status != null) predicates.add(cb.equal(root.get("parseStatus"), status));
			if (StringUtils.hasText(keyword)) {
				String like = "%" + keyword.trim().toLowerCase() + "%";
				var scholarship = root.join("scholarship", jakarta.persistence.criteria.JoinType.LEFT);
				predicates.add(cb.or(
						cb.like(cb.lower(root.get("sourceId")), like),
						cb.like(cb.lower(scholarship.get("title")), like),
						cb.like(cb.lower(scholarship.get("provider")), like)));
			}
			return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
		};
		return rawScholarshipRepository.findAll(spec, pageable).map(this::toIntakeRow);
	}

	public AdminRawDetailResponse rawDetail(Long rawId) {
		RawScholarship raw = rawScholarshipRepository.findById(rawId)
				.orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT));
		Long scholarshipId = raw.getScholarship() == null ? null : raw.getScholarship().getId();
		return new AdminRawDetailResponse(raw.getId(), scholarshipId, raw.getSource(), raw.getSourceId(),
				raw.getSourceUrl(), raw.getRawJson(), raw.getRawHtml(), raw.getParseStatus().name(),
				raw.getParseError(), raw.getCrawledAt(), scholarshipId == null ? null : detail(scholarshipId));
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

	public Page<AdminRawFailureResponse> failures(String keyword, String source, ParseStatus status,
			boolean retryableOnly, Pageable pageable) {
		Specification<RawScholarship> spec = (root, query, cb) -> {
			List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
			if (status == null) {
				predicates.add(root.get("parseStatus").in(
						ParseStatus.FAILED, ParseStatus.SKIPPED, ParseStatus.IMAGE_ONLY));
			} else {
				predicates.add(cb.equal(root.get("parseStatus"), status));
			}
			// 마감 공고는 정상 제외 대상이며 재처리해도 결과가 같으므로 실패 큐에서 숨긴다.
			predicates.add(cb.or(cb.isNull(root.get("parseError")),
					cb.notLike(root.get("parseError"), "%모집%지난 장학금%")));
			if (StringUtils.hasText(source)) predicates.add(cb.equal(root.get("source"), source.trim()));
			if (retryableOnly) predicates.add(cb.like(root.get("source"), "UNIV\\_%", '\\'));
			if (StringUtils.hasText(keyword)) {
				String like = "%" + keyword.trim().toLowerCase() + "%";
				predicates.add(cb.or(cb.like(cb.lower(root.get("sourceId")), like),
						cb.like(cb.lower(root.get("parseError")), like)));
			}
			return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
		};
		return rawScholarshipRepository.findAll(spec, pageable)
				.map(raw -> new AdminRawFailureResponse(
						raw.getId(), raw.getScholarship() == null ? null : raw.getScholarship().getId(),
						raw.getSource(), raw.getSourceId(), raw.getSourceUrl(), raw.getParseStatus().name(),
						raw.getParseError(), raw.getCrawledAt(), raw.getUpdatedAt()));
	}

	public Page<AdminRawFailureResponse> failures(Pageable pageable) {
		return failures(null, null, null, false, pageable);
	}

	public Page<AdminScholarshipAnomalyResponse> anomalies(String keyword, String source,
			RecruitmentStatus status, String anomalyType, Pageable pageable) {
		Specification<Scholarship> base = scholarshipSpec(keyword, source, status, false)
				.and((root, query, cb) -> anomalyPredicate(anomalyType, root, query, cb));
		return scholarshipRepository.findAll(base, pageable)
				.map(value -> new AdminScholarshipAnomalyResponse(
						value.getId(), value.getTitle(), value.getProvider(), name(value.getRecruitmentStatus()),
						value.getApplicationStartAt(), value.getApplicationEndAt(), value.getPrimarySource(),
						anomalyTypes(value)));
	}

	public Page<AdminScholarshipAnomalyResponse> anomalies(Pageable pageable) {
		return anomalies(null, null, null, null, pageable);
	}

	public Page<AdminImageRowResponse> images(String keyword, String source, Boolean hasImage,
			Pageable pageable) {
		Set<Long> imageIds = posterScholarshipIds();
		Specification<Scholarship> spec = scholarshipSpec(keyword, source, null, false)
				.and((root, query, cb) -> {
					if (hasImage == null) return cb.conjunction();
					if (imageIds.isEmpty()) return hasImage ? cb.disjunction() : cb.conjunction();
					return hasImage ? root.get("id").in(imageIds) : cb.not(root.get("id").in(imageIds));
				});
		Page<Scholarship> scholarships = scholarshipRepository.findAll(spec, pageable);
		List<Long> ids = scholarships.stream().map(Scholarship::getId).toList();
		Map<Long, Image> latest = ids.isEmpty() ? Map.of() : imageRepository
				.findAllByEntityTypeAndEntityIdIn(ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, ids)
				.stream().collect(Collectors.toMap(Image::getEntityId, Function.identity(),
						(left, right) -> left.getId() > right.getId() ? left : right));
		List<AdminImageRowResponse> content = scholarships.stream().map(s -> {
			Image image = latest.get(s.getId());
			return new AdminImageRowResponse(s.getId(), s.getTitle(), s.getProvider(), s.getPrimarySource(),
					image == null ? null : image.getId(), image == null ? null : image.getImageType(),
					image == null ? null : image.getOriginalName(), image == null ? null : image.getSourceUrl(),
					image == null ? null : imageStorageService.publicUrl(image.getS3Key()),
					image == null ? null : image.getCreatedAt());
		}).toList();
		return new PageImpl<>(content, pageable, scholarships.getTotalElements());
	}

	private Specification<Scholarship> scholarshipSpec(String keyword, String source,
			RecruitmentStatus status, boolean includeDeleted) {
		return (root, query, cb) -> {
			List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
			if (!includeDeleted) predicates.add(cb.isNull(root.get("deletedAt")));
			if (StringUtils.hasText(keyword)) {
				String like = "%" + keyword.trim().toLowerCase() + "%";
				predicates.add(cb.or(cb.like(cb.lower(root.get("title")), like),
						cb.like(cb.lower(root.get("provider")), like)));
			}
			if (StringUtils.hasText(source)) predicates.add(cb.equal(root.get("primarySource"), source.trim()));
			if (status != null) predicates.add(cb.equal(root.get("recruitmentStatus"), status));
			return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
		};
	}

	private jakarta.persistence.criteria.Predicate anomalyPredicate(String type,
			jakarta.persistence.criteria.Root<Scholarship> root,
			jakarta.persistence.criteria.CriteriaQuery<?> query,
			jakarta.persistence.criteria.CriteriaBuilder cb) {
		var emptyTitle = cb.or(cb.isNull(root.get("title")), cb.equal(cb.trim(root.get("title")), ""));
		var missingProvider = cb.or(cb.isNull(root.get("provider")), cb.equal(cb.trim(root.get("provider")), ""));
		var dateReversed = cb.and(cb.isNotNull(root.get("applicationStartAt")),
				cb.isNotNull(root.get("applicationEndAt")),
				cb.greaterThan(root.get("applicationStartAt"), root.get("applicationEndAt")));
		var openEnded = cb.and(cb.equal(root.get("recruitmentStatus"), RecruitmentStatus.OPEN),
				cb.lessThan(root.get("applicationEndAt"), LocalDateTime.now()));
		var activeStatus = root.get("recruitmentStatus").in(
				RecruitmentStatus.OPEN, RecruitmentStatus.ALWAYS_OPEN);
		var missingLink = cb.and(activeStatus,
				cb.or(cb.isNull(root.get("homepageUrl")), cb.equal(cb.trim(root.get("homepageUrl")), "")),
				cb.or(cb.isNull(root.get("detailUrl")), cb.equal(cb.trim(root.get("detailUrl")), "")));
		var subquery = query.subquery(Long.class);
		var condition = subquery.from(com.wishconnect.domain.scholarship.entity.ScholarshipCondition.class);
		subquery.select(cb.literal(1L)).where(cb.equal(condition.get("scholarship"), root));
		var missingCondition = cb.and(activeStatus, cb.not(cb.exists(subquery)));
		if (StringUtils.hasText(type)) {
			return switch (type) {
				case "EMPTY_TITLE" -> emptyTitle;
				case "MISSING_PROVIDER" -> missingProvider;
				case "DATE_REVERSED" -> dateReversed;
				case "OPEN_BUT_ENDED" -> openEnded;
				case "MISSING_LINK" -> missingLink;
				case "MISSING_CONDITION" -> missingCondition;
				default -> throw new CustomException(ErrorCode.INVALID_INPUT);
			};
		}
		return cb.or(emptyTitle, missingProvider, dateReversed, openEnded, missingLink, missingCondition);
	}

	private AdminIntakeRowResponse toIntakeRow(RawScholarship raw) {
		Scholarship scholarship = raw.getScholarship();
		return new AdminIntakeRowResponse(raw.getId(), scholarship == null ? null : scholarship.getId(),
				scholarship == null ? raw.getSourceId() : scholarship.getTitle(), raw.getSource(),
				raw.getSourceId(), raw.getSourceUrl(), raw.getParseStatus().name(), raw.getParseError(),
				raw.getCrawledAt());
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
