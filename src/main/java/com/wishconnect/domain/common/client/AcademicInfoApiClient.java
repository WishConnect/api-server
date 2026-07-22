package com.wishconnect.domain.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.wishconnect.domain.common.config.AcademicInfoApiProperties;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/*
전국 대학/학과 공공데이터 API 호출 클라이언트입니다.
API 응답 필드명이 서비스별로 조금씩 달라질 수 있어 여러 후보 키를 순서대로 읽습니다.
 */
@Component
public class AcademicInfoApiClient {

	private final RestClient restClient;
	private final AcademicInfoApiProperties properties;

	public AcademicInfoApiClient(AcademicInfoApiProperties properties, RestClient.Builder restClientBuilder) {
		this.properties = properties;
		this.restClient = restClientBuilder.build();
	}

	public List<SchoolItem> fetchSchools() {
		return fetchAll(properties.schoolBaseUrlOrDefault(), properties.schoolPathOrDefault())
				.stream()
				.map(this::toSchoolItem)
				.filter(SchoolItem::hasName)
				.toList();
	}

	public List<MajorItem> fetchMajors() {
		return fetchMajors(fetchSchools());
	}

	public List<MajorItem> fetchMajors(List<SchoolItem> schools) {
		if (!StringUtils.hasText(properties.surveyYear())) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		List<MajorItem> result = new ArrayList<>();
		for (SchoolItem school : schools) {
			if (!StringUtils.hasText(school.schoolId())) {
				continue;
			}
			fetchAll(properties.majorBaseUrlOrDefault(), properties.majorPathOrDefault(), school.schoolId())
					.stream()
					.map(this::toMajorItem)
					.filter(MajorItem::hasName)
					.forEach(result::add);
		}
		return result;
	}

	private List<JsonNode> fetchAll(String baseUrl, String path) {
		return fetchAll(baseUrl, path, null);
	}

	private List<JsonNode> fetchAll(String baseUrl, String path, String schoolId) {
		List<JsonNode> result = new ArrayList<>();
		int pageSize = properties.pageSizeOrDefault();

		for (int page = 1; page <= properties.maxPagesOrDefault(); page++) {
			PageResult pageResult = fetchPage(baseUrl, path, page, pageSize, schoolId);
			List<JsonNode> items = pageResult.items();
			if (items.isEmpty()) {
				break;
			}
			result.addAll(items);
			Integer totalCount = readInt(pageResult.root(), "totalCount", "total_count", "total");
			if (totalCount != null && result.size() >= totalCount) {
				break;
			}
			if (items.size() < pageSize) {
				break;
			}
		}
		return result;
	}

	private PageResult fetchPage(String baseUrl, String path, int page, int pageSize, String schoolId) {
		JsonNode root = restClient.get()
				.uri(buildUri(baseUrl, path, page, pageSize, schoolId))
				.retrieve()
				.body(JsonNode.class);
		return new PageResult(root, extractItems(root));
	}

	private record PageResult(JsonNode root, List<JsonNode> items) {
	}

	private URI buildUri(String baseUrl, String path, int page, int pageSize, String schoolId) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)
				.path(path)
				.queryParam("pageNo", page)
				.queryParam("numOfRows", pageSize)
				.queryParam("page", page)
				.queryParam("perPage", pageSize)
				.queryParam("_type", "json")
				.queryParam("type", "json")
				.queryParam("returnType", "JSON");

		if (StringUtils.hasText(properties.serviceKey())) {
			builder.queryParam("serviceKey", properties.serviceKey());
		}
		if (StringUtils.hasText(properties.surveyYear())) {
			builder.queryParam("svyYr", properties.surveyYear());
		}
		if (StringUtils.hasText(schoolId)) {
			builder.queryParam("schlId", schoolId);
		}
		return builder.build(true).toUri();
	}

	private List<JsonNode> extractItems(JsonNode root) {
		if (root == null || root.isNull()) {
			return List.of();
		}
		JsonNode items = firstExisting(root,
				"/response/body/items/item",
				"/response/body/items",
				"/body/items/item",
				"/items",
				"/data",
				"/body/items");
		if (items == null || items.isMissingNode() || items.isNull()) {
			return List.of();
		}
		if (items.isArray()) {
			List<JsonNode> result = new ArrayList<>();
			items.forEach(result::add);
			return result;
		}
		return List.of(items);
	}

	private SchoolItem toSchoolItem(JsonNode node) {
		return new SchoolItem(
				readText(node, "schlId", "schoolId", "univId", "id"),
				readText(node, "schlKrnNm", "schlFullNm", "korSchlNm", "schoolName",
						"schoolNm", "schlNm", "univName", "univNm", "name"),
				readText(node, "znNm", "mjrAreaNm", "areaNm", "region", "regionName", "area", "adres", "address"),
				readText(node, "schlKndNm", "schoolType", "schoolGubun", "schoolKnd", "type", "estbType")
		);
	}

	private MajorItem toMajorItem(JsonNode node) {
		return new MajorItem(
				readText(node, "korMjrNm", "majorName", "majorNm", "deptName", "deptNm", "mClass", "name"),
				readText(node, "korSrsLclftNm", "korSrsMclftNm", "korSrsSclftNm",
						"majorCategory", "category", "lClass", "largeCategory", "field")
		);
	}

	private String readText(JsonNode node, String... fieldNames) {
		for (String fieldName : fieldNames) {
			JsonNode value = node.get(fieldName);
			if (value != null && !value.isNull() && StringUtils.hasText(value.asText())) {
				return value.asText().trim();
			}
		}
		return null;
	}

	private Integer readInt(JsonNode root, String... fieldNames) {
		for (String fieldName : fieldNames) {
			JsonNode value = root.findValue(fieldName);
			if (value != null && value.canConvertToInt()) {
				return value.asInt();
			}
		}
		return null;
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

	public record SchoolItem(String schoolId, String name, String regionName, String schoolType) {

		boolean hasName() {
			return StringUtils.hasText(name);
		}
	}

	public record MajorItem(String name, String category) {

		boolean hasName() {
			return StringUtils.hasText(name);
		}
	}
}
