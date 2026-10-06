package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.ConditionNecessity;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.NoticeKind;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.entity.SubmissionChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "장학금·원문·조건·서류·선발 일정·이미지를 함께 저장하는 관리자 수기 등록 요청")
public record ScholarshipManualFullRequest(
		@NotBlank @Size(max = 500) String title,
		@Size(max = 200) String provider,
		String summary,
		String description,
		ScholarshipType scholarshipType,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		RecruitmentStatus recruitmentStatus,
		@PositiveOrZero Integer selectionCount,
		@PositiveOrZero Long amount,
		@Size(max = 1000) String homepageUrl,
		@Size(max = 1000) String detailUrl,
		NoticeKind noticeKind,
		boolean combined,
		@Size(max = 300) String submissionMethod,
		SubmissionChannel submissionChannel,
		String submissionEvidence,
		@Size(max = 500) String contact,
		RequirementLevel essayRequirement,
		String essayEvidence,
		RequirementLevel interviewRequirement,
		String interviewEvidence,
		@Valid Source source,
		@Valid List<Condition> conditions,
		@Valid List<Document> documents,
		@Size(max = 1000) String imageSourceUrl,
		@Schema(description = """
				선발 일정. 조건·서류와 의미가 다르다.
				null 또는 생략: 기존 일정 유지 / []: 모두 삭제 / 목록: 그 목록으로 전체 교체(순서는 배열 순서, 출처 MANUAL).
				최대 10행. 검증 실패는 TIMELINE_* 오류와 data(index·field·maxLength)로 돌려준다.""")
		List<TimelineItemRequest> timeline,
		@Schema(description = """
				모집기간 수기 고정(통합 수정에서만 쓴다). 생략하면 모집 시작·마감이 현재 값과 달라질 때 자동으로 true.
				명시하면 그 값을 우선한다 — false 는 "자동 수집 값으로 되돌리기"(다음 동기화·재파싱부터 수집 기간으로 덮임).
				수기 등록·원본 수기 정제에서는 무시한다.""")
		Boolean periodLocked
) {

	/** 선발 일정·기간 고정을 모르는 경로(엑셀 일괄 등록 등)용. timeline=null 이라 기존 일정을 건드리지 않는다. */
	public ScholarshipManualFullRequest(String title, String provider, String summary, String description,
			ScholarshipType scholarshipType, LocalDateTime applicationStartAt, LocalDateTime applicationEndAt,
			RecruitmentStatus recruitmentStatus, Integer selectionCount, Long amount, String homepageUrl,
			String detailUrl, NoticeKind noticeKind, boolean combined, String submissionMethod,
			SubmissionChannel submissionChannel, String submissionEvidence, String contact,
			RequirementLevel essayRequirement, String essayEvidence, RequirementLevel interviewRequirement,
			String interviewEvidence, Source source, List<Condition> conditions, List<Document> documents,
			String imageSourceUrl) {
		this(title, provider, summary, description, scholarshipType, applicationStartAt, applicationEndAt,
				recruitmentStatus, selectionCount, amount, homepageUrl, detailUrl, noticeKind, combined,
				submissionMethod, submissionChannel, submissionEvidence, contact, essayRequirement, essayEvidence,
				interviewRequirement, interviewEvidence, source, conditions, documents, imageSourceUrl, null, null);
	}

	@Schema(description = "수기 등록 근거 원문. 입력 전체는 rawJson에도 자동 보관됩니다.")
	public record Source(
			@Size(max = 1000) String sourceUrl,
			String rawHtml
	) {
	}

	@Schema(description = "지원 자격 또는 우대 조건")
	public record Condition(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ConditionType conditionType,
			ConditionOperator operator,
			ConditionNecessity necessity,
			Integer valueInt,
			Integer valueIntMax,
			@NotBlank String valueString,
			@Schema(description = "지역·전공·가정형태 등 화면에서 선택한 마스터 라벨") List<String> refLabels,
			@Schema(description = "통합 수정 시 기존 숫자 참조 보존용") List<Long> refIds,
			@Schema(description = "통합 수정 시 기존 enum 코드 참조 보존용") List<String> refCodes
	) {
		public Condition(ConditionType conditionType, ConditionOperator operator,
				ConditionNecessity necessity, Integer valueInt, Integer valueIntMax,
				String valueString, List<String> refLabels) {
			this(conditionType, operator, necessity, valueInt, valueIntMax,
					valueString, refLabels, List.of(), List.of());
		}
	}

	@Schema(description = "제출 서류")
	public record Document(
			@NotBlank @Size(max = 200) String name,
			boolean essay,
			@PositiveOrZero Integer displayOrder,
			@Size(max = 1000) String downloadUrl
	) {
	}
}
