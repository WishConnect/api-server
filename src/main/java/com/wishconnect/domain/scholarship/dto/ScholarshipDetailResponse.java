package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 장학금 상세 응답. 노션 명세(GET /api/v1/scholarships/{scholarshipId}) 구조.
 * summary = 요약 정보 테이블(조건 원문 매핑), selectionSchedule = 선발 일정 타임라인,
 * requiredDocuments = 제출 서류 목록.
 */
@Schema(description = "장학금 상세·제출·자격 판정·추천 근거 응답")
public record ScholarshipDetailResponse(
		Long scholarshipId,
		String title,
		String organization,
		String status,
		LocalDateTime deadline,
		Long dDay,
		boolean isScrapped,
		List<String> tags,
		String posterUrl,
		String detailUrl,
		Summary summary,
		@Schema(description = "선발 일정. 접수 단계를 따로 입력하지 않았으면 모집기간 \"서류접수\" 줄이 맨 앞, 그 뒤 일정이 표시 순서대로")
		List<ScheduleStep> selectionSchedule,
		List<RequiredDocument> requiredDocuments,
		@Schema(description = "프로필과 공고를 대조한 추천 이유") List<String> matchReasons,
		@Schema(description = "조건별 MATCH·MISMATCH·UNKNOWN 자격 판정") List<ConditionCheck> conditionChecks,
		/**
		 * 한 공고에 여러 장학금이 실려 있으면 true. 이때 {@code conditionChecks} 의 판정은
		 * 전부 {@code UNKNOWN} 이다 — 조건이 서로 다른 장학금 것이라 판정할 수 없다.
		 * 화면은 "여러 장학금이 포함된 공고입니다. 원문에서 확인하세요" 로 안내하면 된다.
		 */
		@Schema(description = "하나의 공고에 서로 다른 장학금이 여러 개 포함되었는지") boolean combined,
		@Schema(description = "자기소개서·면접 필요 여부와 공고 근거") Selection selection
) {

	/**
	 * 전형 정보 — 자기소개서·면접이 필요한가.
	 *
	 * <p>{@code null} 은 <b>공고에 언급이 없어 모른다</b>는 뜻이다. {@code NOT_REQUIRED}
	 * ("확인했고 없다")와 다르게 그려야 한다 — 전자는 "공고 확인 필요", 후자는 "면접 없음".
	 *
	 * <p>{@code evidence} 는 그렇게 판단한 공고 원문이다. 우리 판단만 보여주는 것보다
	 * 근거 문장을 함께 보여주는 편이 신뢰를 산다. 특히 {@code CONDITIONAL} 은
	 * "무슨 조건인지" 가 원문에 들어 있다 — "서류 합격자에 한해".
	 */
	@Schema(description = "자기소개서·면접 요구사항. null은 판단 불가, NOT_REQUIRED는 불필요를 확인했다는 뜻")
	public record Selection(
			String essayRequirement,
			String essayEvidence,
			String interviewRequirement,
			String interviewEvidence) {
	}

	@Schema(description = "값이 없을 수 있는 상세 요약 필드. 프론트는 null·빈 값을 제외해 가변 목록으로 표시")
	public record Summary(
			String targetAudience,
			String supportAmount,
			String selectedCount,
			String fieldOfStudy,
			String supportType,
			String duplicateAllowed,
			String operatingOrganization,
			String contactInfo,
			String selectionCriteria,
			String gpaRequirement,
			String incomeRequirement,
			String preferredConditions,
			String applicationPeriod,
			String submissionMethod
	) {
	}

	/**
	 * 선발 일정 한 줄.
	 *
	 * <p>순서: 접수(APPLICATION) 단계를 직접 입력하지 않았고 모집기간이 있으면 모집기간으로 만든 "서류접수" 줄이
	 * 맨 앞에 온다. 그 뒤에 관리자가 입력한 일정이 표시 순서대로 온다. 일정이 없으면 "서류접수" 한 줄(또는 빈 목록).
	 */
	@Schema(description = "선발 일정 한 줄")
	public record ScheduleStep(
			@Schema(description = "단계 표시명", example = "면접") String step,
			@Schema(description = """
					날짜 문자열. 하루 "yyyy.MM.dd", 기간 "yyyy.MM.dd ~ yyyy.MM.dd", 미정이면 관리자가 쓴 문구 그대로
					(예: "12월 중 예정"). 모집기간 "서류접수" 줄은 한쪽 날짜만 있으면 "~yyyy.MM.dd" 또는 "yyyy.MM.dd~".""",
					example = "2026.11.20") String date,
			@Schema(description = """
					한국 날짜 기준으로 조회 때 계산한 상태. CLOSED 지남 / CURRENT 진행 중(하루짜리는 당일) / UPCOMING 예정 /
					TBD 날짜 미정. ⚠ 현재는 TBD 를 UPCOMING 으로 바꿔 보낸다 — 프론트가 TBD 를 지원하면 TBD 를 그대로 보낸다.""",
					allowableValues = {"CLOSED", "CURRENT", "UPCOMING", "TBD"}, example = "UPCOMING") String status) {
	}

	public record RequiredDocument(String name, String downloadUrl) {
	}

	/**
	 * 조건 1건에 대한 내 판정.
	 *
	 * <p>{@code result} 는 세 값이다 — {@code MATCH}(충족) / {@code MISMATCH}(불충족) /
	 * {@code UNKNOWN}(판정 불가). 판정 불가를 불충족처럼 보여주면 자격이 있는데도 포기하게 되므로
	 * 화면에서도 구분해서 그려야 한다. 로그인하지 않았거나 프로필이 비어 있으면 전부 판정 불가다.
	 *
	 * <p>{@code necessity} 가 {@code PREFERRED} 인 조건은 불충족이어도 지원할 수 있다(우대사항).
	 */
	@Schema(description = "자격·우대 조건 1건에 대한 현재 사용자 판정")
	public record ConditionCheck(
			String conditionType,
			String necessity,
			String requirement,
			String result,
			String description) {
	}
}
