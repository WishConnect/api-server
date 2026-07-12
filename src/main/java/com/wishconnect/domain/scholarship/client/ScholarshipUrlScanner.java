package com.wishconnect.domain.scholarship.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.wishconnect.domain.scholarship.config.ScholarshipApiProperties;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/*
공공데이터 OAS 문서에서 한국장학재단 학자금지원정보 최신 엔드포인트를 찾는 클래스입니다.
월별로 바뀌는 API path를 날짜 기준 최신순으로 정렬해 동기화 대상 목록을 만듭니다.
 */
@Component
public class ScholarshipUrlScanner {

	private final RestClient restClient;
	private final ScholarshipApiProperties properties;

	public ScholarshipUrlScanner(ScholarshipApiProperties properties) {
		this.restClient = RestClient.create();
		this.properties = properties;
	}

	public ScholarshipEndpoint getLatestEndpoint() {
		return getEndpoints().stream()
			.findFirst()
			.orElse(fallbackEndpoint());
	}

	public List<ScholarshipEndpoint> getEndpoints() {
		try {
			JsonNode root = restClient.get()
				.uri(properties.docsUrlOrDefault())
				.retrieve()
				.body(JsonNode.class);

			if (root != null && root.has("paths")) {
				return extractEndpoints(root.get("paths"));
			}
		} catch (RuntimeException ignored) {
			return List.of(fallbackEndpoint());
		}

		return List.of(fallbackEndpoint());
	}

	private List<ScholarshipEndpoint> extractEndpoints(JsonNode paths) {
		List<ScholarshipEndpoint> candidates = new ArrayList<>();
		Iterator<String> fieldNames = paths.fieldNames();

		while (fieldNames.hasNext()) {
			String pathKey = fieldNames.next();
			JsonNode pathNode = paths.get(pathKey);
			if (pathNode == null || !pathNode.has("get")) {
				continue;
			}

			JsonNode getOperation = pathNode.get("get");
			if (getOperation == null || !getOperation.has("description")) {
				continue;
			}

			String description = getOperation.get("description").asText();
			if (!description.contains("한국장학재단") || !description.contains("학자금지원정보")) {
				continue;
			}

			String dateText = description.substring(description.lastIndexOf("_") + 1).trim();
			LocalDate parsedDate = parseToLocalDate(dateText);
			if (parsedDate != null) {
				candidates.add(new ScholarshipEndpoint(normalizePath(pathKey), parsedDate, description));
			}
		}

		int endpointLimit = properties.endpointLimitOrDefault();
		return candidates.stream()
			.sorted(Comparator.comparing(ScholarshipEndpoint::date).reversed())
			.limit(endpointLimit == 0 ? Long.MAX_VALUE : endpointLimit)
			.toList();
	}

	private ScholarshipEndpoint fallbackEndpoint() {
		return new ScholarshipEndpoint(properties.fallbackPathOrDefault(), null, "fallback");
	}

	private String normalizePath(String path) {
		if (!path.isEmpty() && !path.startsWith("/15028252")) {
			return "/15028252" + path;
		}
		return path;
	}

	private LocalDate parseToLocalDate(String dateText) {
		try {
			if (dateText.matches("\\d{8}")) {
				return LocalDate.parse(dateText, DateTimeFormatter.ofPattern("yyyyMMdd"));
			}
			if (dateText.contains("/")) {
				return LocalDate.parse(dateText, DateTimeFormatter.ofPattern("MM/dd/yyyy"));
			}
		} catch (DateTimeParseException ignored) {
			return null;
		}
		return null;
	}
}
