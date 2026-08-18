package com.wishconnect.domain.scholarship.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 대학 장학공지 본문을 LLM 이 구조화한 결과.
 *
 * <p>모든 필드가 nullable 이다. 본문에 근거가 없으면 값을 지어내지 말고 null 을 넣도록
 * 프롬프트에서 지시하고, 여기서도 그 전제로 다룬다.
 *
 * <p>{@code periodEvidence} 는 환각 방어용이다. LLM 이 신청기간을 뽑았다면 그 근거가 된
 * 본문 문장을 그대로 인용하게 하고, 인용문이 실제 본문에 없으면 기간을 버린다.
 * 잘못된 마감일은 모집 중인 공고를 마감 처리해 노출에서 사라지게 만들기 때문에
 * "못 뽑는 것"보다 "틀리게 뽑는 것"이 훨씬 해롭다.
 *
 * @param title             공고 제목
 * @param provider          운영기관. 교외 장학이면 재단명, 교내면 대학명
 * @param scholarshipType   INTERNAL / EXTERNAL / WORK_STUDY
 * @param applicationStart  신청 시작 (yyyy-MM-dd). 마감만 있으면 null
 * @param applicationEnd    신청 마감 (yyyy-MM-dd)
 * @param periodEvidence    신청기간의 근거가 된 본문 문장 원문
 * @param selectionCount    선발 인원
 * @param amount            장학 금액(원)
 * @param summary           한 문장 요약
 * @param documents         제출서류명 목록
 * @param conditions        지원 자격조건 목록
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParsedNotice(
		String title,
		String provider,
		String scholarshipType,
		String applicationStart,
		String applicationEnd,
		String periodEvidence,
		Integer selectionCount,
		Long amount,
		String summary,
		String noticeKind,
		Boolean combined,
		String submissionMethod,
		String submissionChannel,
		String essayRequirement,
		String essayEvidence,
		String interviewRequirement,
		String interviewEvidence,
		List<String> documents,
		List<Condition> conditions
) {

	/**
	 * 자격조건 하나. {@code evidence} 는 기간과 같은 이유로 <b>본문 원문 인용</b>이어야 한다.
	 *
	 * <p>잘못된 조건은 추천에서 자격 있는 학생을 부당하게 탈락시킨다. 그래서 인용문이 본문에
	 * 실제로 없으면 그 조건은 버린다. 인용문은 그대로 {@code valueString} 이 되어,
	 * 이후 수치 구조화(ConditionExtractionService)의 입력이자 사람의 검증 자료로 남는다.
	 *
	 * <p>1단계가 값까지 뽑는 이유는 본문 맥락을 볼 수 있어서다. 2단계(ConditionExtractionService)는
	 * evidence 텍스트만 받아 본문을 못 보므로, "이건 소득 조건" 이라는 전제가 틀리면 없는 숫자를
	 * 만들어낸다. 대학공지는 여기서 끝내고 2단계는 공공데이터 전용으로 남긴다.
	 *
	 * @param type         {@code ConditionType} 이름
	 * @param evidence     조건의 근거가 된 본문 문장 원문
	 * @param necessity    REQUIRED(자격요건) / PREFERRED(우대사항)
	 * @param refLabels    마스터에서 찾을 라벨. 지역명·가정형태명·전공계열명 등
	 * @param operator     수치 비교 방식 (GTE/LTE/BETWEEN/EQ)
	 * @param valueInt     수치 기준. 평점은 100배 정수, 소득은 분위, 학년은 학기
	 * @param valueIntMax  BETWEEN 의 상한
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Condition(
			String type,
			String evidence,
			String necessity,
			List<String> refLabels,
			String operator,
			Integer valueInt,
			Integer valueIntMax
	) {

		/**
		 * 자격요건인지 우대사항인지. 값이 없으면 자격요건으로 본다 — 우대를 자격으로 잘못 보면
		 * 추천이 좁아질 뿐이지만, 반대는 지원할 수 없는 장학금을 추천하게 된다.
		 */
		public String safeNecessity() {
			return necessity == null || necessity.isBlank() ? "REQUIRED" : necessity.trim();
		}

		public List<String> safeRefLabels() {
			return refLabels == null ? List.of() : refLabels;
		}

		/** 유형과 근거만 있는 조건. 수치·참조가 없는 서술형 요건이 실제로 이 형태로 온다. */
		public static Condition of(String type, String evidence) {
			return new Condition(type, evidence, null, null, null, null, null);
		}
	}

	public List<String> safeDocuments() {
		return documents == null ? List.of() : documents;
	}

	public List<Condition> safeConditions() {
		return conditions == null ? List.of() : conditions;
	}
}
