package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "공공데이터 조건에 마스터 참조를 채운 결과")
public record ConditionRefBackfillResponse(

		@Schema(description = "참조가 비어 있어 시도한 조건 수", example = "200")
		int targetCount,

		@Schema(description = "참조를 하나 이상 채운 조건 수", example = "137")
		int filledCount,

		@Schema(description = "채운 참조 총 개수(한 조건에 여러 개가 붙는다)", example = "184")
		int refCount,

		@Schema(description = "유형별로 채운 조건 수. 어느 유형이 안 풀리는지 보려는 것이다.",
				example = "{\"REGION_RESIDENCY\":52,\"MAJOR_FIELD\":71}")
		Map<String, Integer> filledByType) {
}
