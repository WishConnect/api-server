package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 내리기 전 확인. 사용자 데이터가 얼마나 걸려 있는지, 같은 장학금으로 보이는 다른 공고가 있는지 보여 준다.
 * 중복이 있으면 내리기 대신 병합을 권한다 — 병합은 스크랩·자소서를 남길 쪽으로 옮기지만 내리기는 옮기지 않는다.
 */
@Schema(description = "장학금 내리기 전 확인")
public record ScholarshipDeleteCheckResponse(
		Long scholarshipId,
		String title,
		@Schema(description = "이미 내린 상태인지") boolean deleted,
		@Schema(description = "스크랩한 사용자 수(내리면 목록에서 보이지 않는다)") long scrapCount,
		@Schema(description = "자소서 — 시작 전") long essayNotStartedCount,
		@Schema(description = "자소서 — 작성 중") long essayInProgressCount,
		@Schema(description = "자소서 — 완료") long essayCompletedCount,
		@Schema(description = "이 장학금이 들어간 중복 후보(상태 무관, 최근 10건)") List<Candidate> mergeCandidates,
		@Schema(description = "제목 기준으로 같은 장학금으로 보이는 다른 공고(최대 5건)") List<Similar> similarScholarships,
		@Schema(description = "중복 후보나 비슷한 공고가 있어 병합을 먼저 검토할 만한지") boolean mergeSuggested,
		@Schema(description = "화면에 띄울 주의 문구") List<String> warnings
) {

	public record Candidate(Long candidateId, String status, String origin, Long primaryId, String primaryTitle,
			Long duplicateId, String duplicateTitle) {
	}

	public record Similar(Long scholarshipId, String title, String provider, String recruitmentStatus,
			LocalDateTime applicationEndAt) {
	}
}
