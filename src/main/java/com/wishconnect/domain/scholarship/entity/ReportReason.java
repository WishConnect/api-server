package com.wishconnect.domain.scholarship.entity;

/**
 * 오등록 신고 사유. 피그마 "신고 팝업"의 체크박스와 1:1 대응한다.
 *
 * <p>화면이 체크박스 다중 선택("신고 사유를 모두 선택해 주세요")이라 신고 한 건에 여러 개가 붙는다.
 */
public enum ReportReason {

	// --- 현재 화면에 노출되는 5개 ---

	/** "모집 기간이 지났어요." — 이미 마감됐는데 모집 중으로 표시됨 */
	ALREADY_CLOSED,
	/** "장학금 정보가 잘못되었어요." — 마감일·금액·링크 등 공고 내용이 실제와 다름 */
	WRONG_INFO,
	/** "지원 조건이 달라요." — 학년·소득분위·전공 등 지원 자격이 실제와 다름 */
	WRONG_CONDITION,
	/** "중복된 장학금이에요." — 같은 장학금이 중복 등록됨 */
	DUPLICATE,
	/** "기타" — detail 에 상세 기재 */
	OTHER,

	// --- 아래 3개는 화면에서 내려간 옛 선택지다 ---
	// 화면이 "장학금 정보가 잘못되었어요." 한 칸으로 묶으면서 쓰이지 않게 됐지만,
	// 이미 접수된 신고가 이 값으로 저장돼 있어 남겨 둔다. 새 신고에는 쓰지 않는다.

	/** @deprecated {@link #WRONG_INFO} 로 대체됨. 기존 신고 조회용으로만 남는다. */
	@Deprecated
	WRONG_DEADLINE,
	/** @deprecated {@link #WRONG_INFO} 로 대체됨. 기존 신고 조회용으로만 남는다. */
	@Deprecated
	WRONG_AMOUNT,
	/** @deprecated {@link #WRONG_INFO} 로 대체됨. 기존 신고 조회용으로만 남는다. */
	@Deprecated
	BROKEN_LINK
}
