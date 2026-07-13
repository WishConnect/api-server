package com.wishconnect.domain.scholarship.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.wishconnect.domain.scholarship.config.ScholarshipApiProperties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/*
공공데이터포털 장학금 API를 호출하는 클라이언트입니다.
ScholarshipUrlScanner가 찾아준 엔드포인트들을 page/perPage 방식으로 순회하며 원본 JSON 목록을 가져옵니다.
 */
@Component
public class ScholarshipApiClient {

	private static final Logger log = LoggerFactory.getLogger(ScholarshipApiClient.class);

	private final RestClient restClient;
	private final ScholarshipApiProperties properties;
	private final ScholarshipUrlScanner scholarshipUrlScanner;

	public ScholarshipApiClient(
		RestClient scholarshipRestClient,
		ScholarshipApiProperties properties,
		ScholarshipUrlScanner scholarshipUrlScanner
	) {
		this.restClient = scholarshipRestClient;
		this.properties = properties;
		this.scholarshipUrlScanner = scholarshipUrlScanner;
	}

    //장학금을 전부 가져오는 시작점(api 호출)
	public List<ScholarshipApiItem> fetchScholarships() {
		List<ScholarshipEndpoint> endpoints = resolveEndpoints();
		List<ScholarshipEndpoint> processingEndpoints = new ArrayList<>(endpoints);
		processingEndpoints.sort((left, right) -> {
			if (left.date() == null && right.date() == null) {
				return 0;
			}
			if (left.date() == null) {
				return -1;
			}
			if (right.date() == null) {
				return 1;
			}
			return left.date().compareTo(right.date());
		});
		List<ScholarshipApiItem> result = new ArrayList<>();
		log.info("Scholarship API endpoint count: {}", endpoints.size());

		for (ScholarshipEndpoint endpoint : processingEndpoints) {
			log.info("Fetching scholarship endpoint. date={}, description={}, path={}", endpoint.dateText(), endpoint.description(), endpoint.path());
			result.addAll(fetchScholarships(endpoint));
		}

		return result;
	}

    //엔드포인트별로 장학금 데이터를 가져오는 메서드(하나의 api안에 있는 데이터)
	private List<ScholarshipApiItem> fetchScholarships(ScholarshipEndpoint endpoint) {
		int page = 1;
		int perPage = properties.perPageOrDefault();
		List<ScholarshipApiItem> result = new ArrayList<>();

		while (true) {
			int currentPage = page;
			JsonNode root = restClient.get()
				.uri(uriBuilder -> buildUri(uriBuilder, endpoint.path(), currentPage, perPage))
				.retrieve()
				.body(JsonNode.class);

			List<JsonNode> pageItems = extractItems(root);
			log.info("Fetched scholarship endpoint. date={}, page={}, count={}", endpoint.dateText(), currentPage, pageItems.size());

			pageItems.stream()
				.map(item -> new ScholarshipApiItem(endpoint, item))
				.forEach(result::add);

			int totalCount = readTotalCount(root);
			if (pageItems.isEmpty() || totalCount <= 0 || result.size() >= totalCount) {
				break;
			}
			page++;
		}

		return result;
	}

	private java.net.URI buildUri(UriBuilder uriBuilder, String path, int page, int perPage) {
		UriBuilder builder = uriBuilder.path(path)
			.queryParam("page", page)
			.queryParam("perPage", perPage)
			.queryParam("returnType", "JSON");

		if (StringUtils.hasText(properties.serviceKey())) {
			builder.queryParam("serviceKey", properties.serviceKey());
		}
		return builder.build();
	}

    //엔드포인트 가져오는 메서드
	private List<ScholarshipEndpoint> resolveEndpoints() {
		String configuredPath = properties.requestPath();
		if (configuredPath.equals("/") || configuredPath.equalsIgnoreCase("latest")) {
			return List.of(scholarshipUrlScanner.getLatestEndpoint());
		}
		if (configuredPath.equalsIgnoreCase("all")) {
			return scholarshipUrlScanner.getEndpoints();
		}
		return List.of(new ScholarshipEndpoint(configuredPath, null, "configured"));
	}

	private List<JsonNode> extractItems(JsonNode root) {
		if (root == null || root.isNull()) {
			return List.of();
		}

		JsonNode items = firstExisting(root, "/items", "/data", "/response/body/items/item", "/response/body/items");
		if (items == null) {
			items = root;
		}

		if (items.isArray()) {
			List<JsonNode> result = new ArrayList<>();
			items.forEach(result::add);
			return result;
		}

		return List.of(items);
	}

	private int readTotalCount(JsonNode root) {
		if (root == null || root.isNull()) {
			return 0;
		}

		for (String fieldName : List.of("totalCount", "total_count", "total")) {
			JsonNode value = root.get(fieldName);
			if (value != null && value.canConvertToInt()) {
				return value.asInt();
			}
		}

		return 0;
	}

	private JsonNode firstExisting(JsonNode root, String... paths) {
		for (String path : paths) {
			JsonNode node = root.at(path);
			if (!node.isMissingNode() && !node.isNull()) {
				return node;
			}
		}
		return null;
	}
}
