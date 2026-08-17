package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.ReportReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 사용자 오등록 신고 요청.
 *
 * <p>피그마 "신고 팝업"이 체크박스 다중 선택이라 사유는 목록으로 받는다.
 */
public record ScholarshipReportRequest(
		@Schema(description = "신고 사유(하나 이상). 중복은 무시된다.",
				example = "[\"ALREADY_CLOSED\", \"WRONG_CONDITION\"]")
		@NotEmpty List<ReportReason> reasons,

		// 화면 카운터가 0/200 이라 200 자로 맞춘다. 기타를 골라도 입력은 선택사항이다.
		@Schema(description = "상세 내용(선택). 최대 200자")
		@Size(max = 200) String detail
) {
}
