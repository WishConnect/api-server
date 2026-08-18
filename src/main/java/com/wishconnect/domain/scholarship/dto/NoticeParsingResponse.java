package com.wishconnect.domain.scholarship.dto;

import java.util.List;

/**
 * 대학 장학공지 LLM 파싱 배치 결과.
 *
 * @param targetCount  파싱을 시도한 원본 건수
 * @param parsedCount  정제 저장(또는 갱신)에 성공한 건수
 * @param skippedCount 본문이 없어 건너뛴 건수
 * @param failedCount  LLM 호출·응답 파싱 실패 건수
 * @param dryRun       true 면 DB 에 쓰지 않고 결과만 반환한 것
 * @param items        건별 결과. 정규식 결과와 비교 검증할 때 쓴다
 *
 * <p>조건·서류·포스터 건수를 함께 담는다. 예전에는 제목과 기간만 담아서, dryRun 으로는
 * 파서가 하는 일의 절반밖에 확인할 수 없었다. 조건 추출이 통째로 망가져도 dryRun 은 멀쩡해 보였다.
 */
public record NoticeParsingResponse(
		int targetCount,
		int parsedCount,
		int skippedCount,
		int failedCount,
		boolean dryRun,
		List<Item> items
) {

	/**
	 * 건별 파싱 결과.
	 *
	 * <p>{@code beforePeriod} / {@code afterPeriod} 를 나란히 담는 이유는, 정규식으로 파싱해둔
	 * 기존 값과 LLM 결과를 사람이 비교해야 하기 때문이다. LLM 이 스스로 채점하면 순환이므로
	 * 판정은 사람이 하고, 여기서는 비교할 재료만 제공한다.
	 *
	 * @param rawId        raw_scholarship.id
	 * @param source       출처 (UNIV_CAU 등)
	 * @param sourceUrl    원문 링크. 사람이 열어보고 판정할 때 쓴다
	 * @param status       PARSED / SKIPPED / FAILED
	 * @param title        LLM 이 뽑은 제목
	 * @param beforePeriod 기존(정규식) 신청기간
	 * @param afterPeriod  LLM 신청기간
	 * @param conditionCount 근거 검증까지 통과해 저장될 조건 수
	 * @param documentCount  제출서류 수
	 * @param posterFound    본문에서 포스터 이미지를 찾았는가
	 * @param note         건너뜀·실패 사유, 또는 기간이 폐기된 이유
	 */
	public record Item(
			Long rawId,
			String source,
			String sourceUrl,
			String status,
			String title,
			String beforePeriod,
			String afterPeriod,
			int conditionCount,
			int documentCount,
			boolean posterFound,
			String note
	) {
	}
}
