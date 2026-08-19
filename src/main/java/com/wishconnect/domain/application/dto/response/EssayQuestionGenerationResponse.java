package com.wishconnect.domain.application.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 장학금 맞춤 자기소개서 문항 생성 응답.
 *
 * <p>{@code source} 로 <b>맞춤 문항이 적용됐는지 기본 문항이 유지됐는지</b>를 알려준다.
 * 공고에 근거가 부족하면 지어낸 문항 대신 기본 문항을 그대로 둔다. 화면은 이 값으로
 * "이 장학금에 맞춘 문항입니다" 같은 안내를 붙일지 정하면 된다.
 */
@Schema(description = "자기소개서 문항 생성 결과. 근거가 부족하면 기본 문항을 유지한다.")
public record EssayQuestionGenerationResponse(
		@Schema(description = "GENERATED = 공고 기반 맞춤 문항으로 교체됨, "
				+ "DEFAULT = 근거 부족으로 기본 문항 유지", example = "GENERATED")
		Source source,

		@Schema(description = "현재 지원서의 문항 목록 (questionOrder 오름차순).")
		List<Item> questions,

		@Schema(description = "DEFAULT 인 이유. GENERATED 면 null.",
				example = "공고에서 문항 근거를 찾지 못했습니다.")
		String reason
) {

	public enum Source {
		/** 공고를 근거로 만든 맞춤 문항으로 교체했다. */
		GENERATED,
		/** 근거가 부족해 기본 문항을 그대로 뒀다. */
		DEFAULT
	}

	@Schema(description = "자기소개서 문항 한 건.")
	public record Item(
			@Schema(description = "문항 ID. 이후 인터뷰·답변 API 에 쓴다.", example = "12")
			Long questionId,

			@Schema(description = "노출 순서 (1부터).", example = "1")
			int order,

			@Schema(description = "문항 이름. 화면 탭에 쓰인다.", example = "지원 동기")
			String title,

			@Schema(description = "학생에게 보여줄 질문.",
					example = "이 장학금에 지원하게 된 계기와 이유를 서술해주세요.")
			String description,

			@Schema(description = "권장 글자수 제한.", example = "800")
			Integer charLimit
	) {
	}
}
