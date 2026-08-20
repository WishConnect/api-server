package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 본문 근거로 거주 요건 조건을 채운 결과.
 *
 * <p>{@code dryRun} 으로 돌리면 아무것도 저장하지 않고 {@code samples} 만 채워 준다.
 * 켜기 전에 <b>무엇이 차단될지</b>를 눈으로 확인하기 위한 것이다 — 추천이 갑자기 비어버리는
 * 사고는 배포 후에 알아채면 늦다.
 */
@Schema(description = "거주 요건 조건 백필 결과")
public record RegionConditionBackfillResponse(

		@Schema(description = "실제 저장 없이 미리보기만 했는지") boolean dryRun,
		@Schema(description = "조건이 없어 검사한 공고 수") int scanned,
		@Schema(description = "본문에서 거주 요건 근거를 찾은 공고 수") int matched,
		@Schema(description = "지역 마스터까지 해석돼 실제로 채워진(또는 채워질) 공고 수") int filled,
		@Schema(description = "근거는 찾았지만 어느 지역인지 해석하지 못한 공고 수") int unresolved,
		@Schema(description = "확인용 표본. dryRun 이면 전부, 아니면 앞쪽 일부") List<Sample> samples) {

	@Schema(description = "무엇을 근거로 어느 지역이라 판단했는지")
	public record Sample(
			@Schema(description = "장학금 ID") Long scholarshipId,
			@Schema(description = "장학금 제목") String title,
			@Schema(description = "판단 근거가 된 본문 문장") String evidence,
			@Schema(description = "해석된 지역명. 비어 있으면 해석 실패") List<String> regions) {
	}
}
