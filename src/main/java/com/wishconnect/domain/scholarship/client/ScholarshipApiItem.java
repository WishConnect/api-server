package com.wishconnect.domain.scholarship.client;

import com.fasterxml.jackson.databind.JsonNode;

/*
외부 API에서 가져온 원본 JSON과 해당 JSON이 나온 엔드포인트 정보를 함께 들고 다니는 값 객체입니다.
월별 엔드포인트를 여러 개 호출하므로, 저장/응답 단계에서 출처를 잃지 않기 위해 사용합니다.
 */
public record ScholarshipApiItem(
	ScholarshipEndpoint endpoint,
	JsonNode payload
) {

	public String endpointPath() {
		return endpoint.path();
	}
}
