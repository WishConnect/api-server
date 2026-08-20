package com.wishconnect.domain.scholarship.dto;

import java.time.LocalDateTime;

/**
 * 중복 탐지의 <b>묶기(blocking)</b> 단계에서만 쓰는 최소 정보.
 *
 * <p>묶기는 제목만 있으면 되고 LLM 을 쓰지 않아 비용이 없다. 그래서 <b>전체 공고</b>를 대상으로
 * 돌린다 — 예전처럼 최신 N건만 보면 중복 쌍이 같은 창에 함께 담겨야만 잡히는데, 실제 중복은
 * 대부분 며칠 차이로 들어온 <b>출처가 다른 쌍</b>이라 그 조건이 거의 성립하지 않았다.
 *
 * <p>엔티티를 통째로 읽지 않는 이유는 그 반대다. 전체를 로드하면 공고 수만큼 메모리를 쓰는데,
 * 운영은 t3.small 이고 힙 상한이 1GB 다. 실제로 LLM 에 넘길 묶음만 나중에 엔티티로 읽는다.
 */
public record DedupScanRow(Long id, String title, LocalDateTime dedupScannedAt) {

	/** 한 번도 중복 검사를 거치지 않았는지. */
	public boolean neverScanned() {
		return dedupScannedAt == null;
	}
}
