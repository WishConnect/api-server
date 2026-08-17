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
		List<String> documents
) {

	public List<String> safeDocuments() {
		return documents == null ? List.of() : documents;
	}
}
