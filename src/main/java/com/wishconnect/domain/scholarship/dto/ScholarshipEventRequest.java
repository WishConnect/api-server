package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.ScholarshipEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "추천 노출·클릭 기록 요청. 노출은 화면 단위로 모아서 한 번에 보낸다.")
public record ScholarshipEventRequest(

		@NotEmpty
		@Size(max = 100, message = "한 번에 100건까지 보낼 수 있습니다.")
		@Valid
		@Schema(description = "한 화면에서 발생한 추천 이벤트. 1~100건")
		List<Event> events) {

	@Schema(description = "추천 카드 노출·클릭 이벤트. curated 응답의 값을 재계산하지 말고 그대로 보낸다.")
	public record Event(

			@NotNull
			@Schema(description = "장학금 id", example = "1024")
			Long scholarshipId,

			@NotNull
			@Schema(description = "IMPRESSION(노출) / CLICK(카드 클릭) / APPLY_CLICK(원문으로 나감) "
					+ "/ DISMISS(추천에서 치움) / UNSCRAP(스크랩 취소)", example = "IMPRESSION")
			ScholarshipEventType eventType,

			@Schema(description = "목록에서 몇 번째로 보였는가(1부터). 목록 밖이면 생략.", example = "3")
			Integer position,

			@Schema(description = "노출 당시 화면에 표시된 매칭 점수", example = "80")
			Integer matchScore,

			@Schema(description = "어느 화면인가", example = "PERSONALIZED")
			String viewMode,

			@Schema(description = "어느 섹션인가. featured(마감임박) / campus(교내) / other(추천) "
					+ "/ ineligible(조건 미충족). 섹션별로 나눠 봐야 무엇이 먹히는지 알 수 있다.",
					example = "other")
			String section,

			@Schema(description = "노출 당시의 점수식 판. 판을 올린 뒤 지표가 좋아졌는지 비교하는 데 쓴다.",
					example = "v2")
			String rankerVersion) {
	}
}
