package com.wishconnect.domain.scholarship.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 장학금 상세 응답. 노션 명세(GET /api/v1/scholarships/{scholarshipId}) 구조.
 * summary = 요약 정보 테이블(조건 원문 매핑), selectionSchedule = 선발 일정 타임라인,
 * requiredDocuments = 제출 서류 목록.
 */
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
		List<ScheduleStep> selectionSchedule,
		List<RequiredDocument> requiredDocuments,
		List<String> matchReasons,
		List<ConditionCheck> conditionChecks,
		Selection selection
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
	public record Selection(
			String essayRequirement,
			String essayEvidence,
			String interviewRequirement,
			String interviewEvidence) {
	}

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

	public record ScheduleStep(String step, String date, String status) {
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
	public record ConditionCheck(
			String conditionType,
			String necessity,
			String requirement,
			String result,
			String description) {
	}
}
