package com.wishconnect.domain.scholarship.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.client.ScholarshipApiClient;
import com.wishconnect.domain.scholarship.client.ScholarshipApiItem;
import com.wishconnect.domain.scholarship.client.ScholarshipEndpoint;
import com.wishconnect.domain.scholarship.config.ScholarshipApiProperties;
import com.wishconnect.domain.scholarship.dto.ScholarshipEndpointSyncResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipSyncResponse;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.util.ScholarshipMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/*
장학금 동기화 전체 흐름을 조율하는 서비스입니다.
외부 API 호출, raw 저장, 정제 테이블 저장 순서를 관리하고 세부 파싱은 ScholarshipMapper에 위임합니다.
 */
@Service
@Profile("!test")
public class ScholarshipSyncService {

	private static final Logger log = LoggerFactory.getLogger(ScholarshipSyncService.class);
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final ScholarshipApiClient scholarshipApiClient;
	private final ScholarshipApiProperties properties;
	private final RawScholarshipRepository rawScholarshipRepository;
	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipDocumentRepository scholarshipDocumentRepository;
	private final ScholarshipConditionRepository scholarshipConditionRepository;
	private final ScholarshipMapper scholarshipMapper;
	private final TransactionTemplate transactionTemplate;
	private final ObjectMapper objectMapper;

	public ScholarshipSyncService(
		ScholarshipApiClient scholarshipApiClient,
		ScholarshipApiProperties properties,
		RawScholarshipRepository rawScholarshipRepository,
		ScholarshipRepository scholarshipRepository,
		ScholarshipDocumentRepository scholarshipDocumentRepository,
		ScholarshipConditionRepository scholarshipConditionRepository,
		ScholarshipMapper scholarshipMapper,
		TransactionTemplate transactionTemplate,
		ObjectMapper objectMapper
	) {
		this.scholarshipApiClient = scholarshipApiClient;
		this.properties = properties;
		this.rawScholarshipRepository = rawScholarshipRepository;
		this.scholarshipRepository = scholarshipRepository;
		this.scholarshipDocumentRepository = scholarshipDocumentRepository;
		this.scholarshipConditionRepository = scholarshipConditionRepository;
		this.scholarshipMapper = scholarshipMapper;
		this.transactionTemplate = transactionTemplate;
		this.objectMapper = objectMapper;
	}

	public ScholarshipSyncResponse sync() {
		List<ScholarshipApiItem> items = scholarshipApiClient.fetchScholarships();
		log.info("Saving raw scholarships. count={}", items.size());
		Map<ScholarshipEndpoint, Integer> endpointCounts = countByEndpoint(items);
		int savedCount = 0;
		int failedCount = 0;

		for (ScholarshipApiItem item : items) {
			try {
				transactionTemplate.executeWithoutResult(status -> saveItem(item));
				savedCount++;
				if (savedCount % 100 == 0) {
					log.info("Saved raw scholarships. savedCount={}, failedCount={}", savedCount, failedCount);
				}
			} catch (RuntimeException exception) {
				failedCount++;
				log.warn("Failed to save raw scholarship. endpointPath={}", item.endpointPath(), exception);
			}
		}

		log.info("Finished saving raw scholarships. fetchedCount={}, savedCount={}, failedCount={}", items.size(), savedCount, failedCount);
		return new ScholarshipSyncResponse(items.size(), savedCount, failedCount, toEndpointResponses(endpointCounts));
	}

	private void saveItem(ScholarshipApiItem item) {
		String endpointPath = item.endpointPath();
		String resolvedSourceId = resolveSourceId(endpointPath, item.payload());
		String sourceUrl = resolveSourceUrl(endpointPath);

		Map<String, Object> rawJson = objectMapper.convertValue(item.payload(), MAP_TYPE);
		RawScholarship rawScholarship = rawScholarshipRepository
			.findBySourceAndSourceId(properties.sourceName(), resolvedSourceId)
			.orElseGet(() -> RawScholarship.builder()
				.source(properties.sourceName())
				.sourceUrl(sourceUrl)
				.sourceId(resolvedSourceId)
				.rawJson(rawJson)
				.parseStatus(ParseStatus.PENDING)
				.build());

		rawScholarship.updateRawData(sourceUrl, rawJson);
		if (scholarshipMapper.isClosed(item.payload())) {
			deleteExistingParsedData(rawScholarship.getScholarship());
			rawScholarship.markSkipped("모집종료일이 지난 장학금입니다.");
			rawScholarshipRepository.save(rawScholarship);
			return;
		}

		Scholarship previouslyLinkedScholarship = rawScholarship.getScholarship();
		String dedupKey = scholarshipMapper.createDedupKey(item.payload());
		Scholarship existingScholarship = scholarshipRepository.findByDedupKey(dedupKey)
			.orElse(previouslyLinkedScholarship);
		Scholarship scholarship = scholarshipRepository.save(
			scholarshipMapper.toScholarship(item.payload(), existingScholarship, properties)
		);
		rawScholarship.markParsed(scholarship);
		rawScholarshipRepository.save(rawScholarship);
		deletePreviousParsedDataIfUnused(previouslyLinkedScholarship, scholarship);
		replaceDocuments(scholarship, item.payload());
		replaceConditions(scholarship, item.payload());
	}

	private void deletePreviousParsedDataIfUnused(Scholarship previousScholarship, Scholarship currentScholarship) {
		if (previousScholarship == null || Objects.equals(previousScholarship.getId(), currentScholarship.getId())) {
			return;
		}

		rawScholarshipRepository.flush();
		if (rawScholarshipRepository.countByScholarship(previousScholarship) == 0) {
			deleteExistingParsedData(previousScholarship);
		}
	}

	private void deleteExistingParsedData(Scholarship scholarship) {
		if (scholarship == null) {
			return;
		}
		scholarshipDocumentRepository.deleteByScholarship(scholarship);
		scholarshipConditionRepository.deleteByScholarship(scholarship);
		scholarship.softDelete();
	}

	private void replaceDocuments(Scholarship scholarship, JsonNode item) {
		scholarshipDocumentRepository.deleteByScholarship(scholarship);
		scholarshipDocumentRepository.saveAll(scholarshipMapper.toDocuments(scholarship, item));
	}

	private void replaceConditions(Scholarship scholarship, JsonNode item) {
		scholarshipConditionRepository.deleteByScholarship(scholarship);
		scholarshipConditionRepository.saveAll(scholarshipMapper.toConditions(scholarship, item));
	}

	private String resolveSourceId(String endpointPath, JsonNode item) {
		return endpointPath + ":" + sha256(item.toString());
	}

	private String resolveSourceUrl(String endpointPath) {
		if (StringUtils.hasText(properties.sourceUrl())) {
			return properties.sourceUrl();
		}
		return properties.baseUrlOrDefault() + endpointPath;
	}

	private Map<ScholarshipEndpoint, Integer> countByEndpoint(List<ScholarshipApiItem> items) {
		Map<ScholarshipEndpoint, Integer> result = new LinkedHashMap<>();
		for (ScholarshipApiItem item : items) {
			result.merge(item.endpoint(), 1, Integer::sum);
		}
		return result;
	}

	private List<ScholarshipEndpointSyncResponse> toEndpointResponses(Map<ScholarshipEndpoint, Integer> endpointCounts) {
		return endpointCounts.entrySet()
			.stream()
			.map(entry -> new ScholarshipEndpointSyncResponse(
				entry.getKey().dateText(),
				entry.getKey().description(),
				entry.getKey().path(),
				entry.getValue()
			))
			.toList();
	}

	private String sha256(String value) {
		try {
			MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
			byte[] digest = messageDigest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder();
			for (byte b : digest) {
				result.append(String.format("%02x", b));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is not available.", exception);
		}
	}

	/** 마감일이 지난 공고를 일괄 CLOSED/비활성 처리한다(배치 선행 스텝). */
	@Transactional
	public int closeExpired() {
		return scholarshipRepository.closeExpired(java.time.LocalDateTime.now());
	}
}
